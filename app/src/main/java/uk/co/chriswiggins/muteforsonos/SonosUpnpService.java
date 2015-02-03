package uk.co.chriswiggins.muteforsonos;

import org.fourthline.cling.UpnpServiceConfiguration;
import org.fourthline.cling.android.AndroidUpnpServiceConfiguration;
import org.fourthline.cling.android.AndroidUpnpServiceImpl;


/**
 * Extends AndroidUpnpServiceImpl in order to keep devices in the registry at
 * all times.
 */
public class SonosUpnpService extends AndroidUpnpServiceImpl {

  @Override
  protected UpnpServiceConfiguration createConfiguration() {
    return new AndroidUpnpServiceConfiguration() {
      @Override
      public Integer getRemoteDeviceMaxAgeSeconds() {
        // Keep devices in registry, even if they haven't sent any ALIVE
        // messages (they occasionally don't seem to...). See Cling user
        // manual section 6.5.1.
        return 0;
      }
    };
  }
}
