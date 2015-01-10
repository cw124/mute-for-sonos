package uk.co.chriswiggins.sonosmute;

import org.teleal.cling.android.AndroidUpnpService;
import org.teleal.cling.controlpoint.ControlPoint;


public class AndroidControlPointProvider implements ControlPointProvider {

  private AndroidUpnpService upnpService;

  public AndroidControlPointProvider(AndroidUpnpService upnpService) {
    this.upnpService = upnpService;
  }


  @Override
  public ControlPoint getControlPoint() {
    return upnpService.getControlPoint();
  }

}
