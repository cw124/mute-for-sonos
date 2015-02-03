package uk.co.chriswiggins.muteforsonos;

import android.util.Log;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UDAServiceId;
import org.fourthline.cling.support.renderingcontrol.callback.GetMute;
import org.fourthline.cling.support.renderingcontrol.callback.SetMute;



/**
 * Represents an interface with a specific Sonos system on the network.
 * If you have multiple Sonos systems in your house, you will have multiple
 * instances of this class.
 */
public class Sonos {

  private static final String TAG = "Sonos";

  private String name;
  private AndroidUpnpService upnpService;
  private RemoteDevice sonosDevice;
  private Service service;

  private boolean previousMute = false;



  public Sonos(String name, AndroidUpnpService upnpService, RemoteDevice sonosDevice) {
    this.name = name;
    this.upnpService = upnpService;
    this.sonosDevice = sonosDevice;
    this.service = sonosDevice.findService(new UDAServiceId("RenderingControl"));
  }



  public String getName() {
    return name;
  }


  /**
   * Mutes this Sonos system, remembering the previous mute state (as queried
   * here and now) so it can be restored by restoreMute.
   */
  public void mute() {
    Log.d(TAG, "Getting current mute state for " + name);

    upnpService.getControlPoint().execute(new GetMute(service) {
      @Override
      public void received(ActionInvocation actionInvocation, boolean currentMute) {
        Log.i(TAG, "Got mute state for " + name + ": " + currentMute);
        previousMute = currentMute;
        setMute(true);
      }

      @Override
      public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
        Log.w(TAG, "Failed to get mute state for " + name + ": " + defaultMsg);
      }
    });
  }


  /**
   * Restores the mute state of this Sonos system to what it was before mute
   * was last called.
   */
  public void restoreMute() {
    setMute(previousMute);
  }


  /**
   * Sets the mute state of this Sonos system to that given.
   */
  private void setMute(boolean mute) {
    Log.d(TAG, "Setting mute to " + mute + " on " + name);

    upnpService.getControlPoint().execute(new SetMute(service, mute) {
      @Override
      public void success(ActionInvocation invocation) {
        Log.d(TAG, "Successfully set mute state for "+ name);
      }

      @Override
      public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
        Log.w(TAG, "Failed to set mute state for " + name + ": " + defaultMsg);
      }
    });
  }



  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Sonos)) {
      return false;
    }
    final Sonos that = (Sonos) o;
    return this.sonosDevice.getIdentity().equals(that.sonosDevice.getIdentity());
  }



  @Override
  public int hashCode() {
    return this.sonosDevice.getIdentity().hashCode();
  }

}
