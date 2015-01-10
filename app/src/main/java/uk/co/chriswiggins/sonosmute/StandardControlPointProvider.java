package uk.co.chriswiggins.sonosmute;

import org.teleal.cling.UpnpService;
import org.teleal.cling.controlpoint.ControlPoint;


public class StandardControlPointProvider implements ControlPointProvider {

  private UpnpService upnpService;

  public StandardControlPointProvider(UpnpService upnpService) {
    this.upnpService = upnpService;
  }


  @Override
  public ControlPoint getControlPoint() {
    return upnpService.getControlPoint();
  }

}
