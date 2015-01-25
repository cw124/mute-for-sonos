package uk.co.chriswiggins.muteforsonos;

import org.fourthline.cling.UpnpService;
import org.fourthline.cling.controlpoint.ControlPoint;


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
