package uk.co.chriswiggins.muteforsonos;

import android.util.Log;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.controlpoint.ActionCallback;
import org.fourthline.cling.controlpoint.SubscriptionCallback;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.gena.CancelReason;
import org.fourthline.cling.model.gena.GENASubscription;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Action;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.meta.RemoteService;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.state.StateVariableValue;
import org.fourthline.cling.model.types.UDAServiceId;
import org.xml.sax.SAXException;

import java.util.Map;


/**
 * Represents and interfaces with a specific Sonos system on the network.
 * If you have multiple Sonos systems in your house, you will have multiple
 * instances of this class.
 */
public class Sonos {

  private static final String TAG = "Sonos";

  private String name;
  private AndroidUpnpService upnpService;
  private RemoteDevice sonosDevice;

  private Boolean muted = null;


  public Sonos(String name, AndroidUpnpService upnpService, RemoteDevice sonosDevice) {
    this.name = name;
    this.upnpService = upnpService;
    this.sonosDevice = sonosDevice;

    RemoteService service = sonosDevice.findService(new UDAServiceId("RenderingControl"));

    SubscriptionCallback callback = new SubscriptionCallback(service, 600) {

      @Override
      public void established(GENASubscription sub) {
        Log.d(TAG, "Subscription callback established (RenderingControl): " + sub.getSubscriptionId());
      }

      @Override
      protected void failed(GENASubscription subscription, UpnpResponse responseStatus, Exception exception, String defaultMsg) {
        Log.w(TAG, "Failed: " + defaultMsg);
      }

      @Override
      public void ended(GENASubscription sub, CancelReason reason, UpnpResponse response) {
        assert reason == null;
        Log.i(TAG, "Ended");
      }

      public void eventReceived(GENASubscription sub) {

        Log.d(TAG, "Received an event from Sonos: " + sub.getCurrentSequence().getValue());

        Map<String, StateVariableValue> values = sub.getCurrentValues();

        Log.v(TAG, "Values: " + values);

        StateVariableValue lastChange = values.get("LastChange");

        if (lastChange == null) {
          Log.v(TAG, "LastChange is null");

        } else {
          Log.v(TAG, "LastChange is: " + lastChange.toString());
          try {
            Map<String, StateVariableValue> stateMap = SonosXMLParser.getRcEntriesFromString(lastChange.toString());

            Log.d(TAG, "Parse is: " + stateMap);

            if (stateMap.containsKey("MuteMaster")) {
              muted = stateMap.get("MuteMaster").getValue().equals("1");
              Log.d(TAG, "Got update event from Sonos to say muted is now set to " + muted);
            }

          } catch (SAXException e) {
            e.printStackTrace();
          }
        }

      }

      public void eventsMissed(GENASubscription sub, int numberOfMissedEvents) {
        Log.i(TAG, "Missed events: " + numberOfMissedEvents);
      }

    };

    upnpService.getControlPoint().execute(callback);

  }


  public String getName() {
    return name;
  }


  public Boolean isMuted() {
    return muted;
  }


  public void mute(boolean mute) {
    Log.d(TAG, "Setting mute to " + mute);

    Service service = sonosDevice.findService(new UDAServiceId("RenderingControl"));
    if (service != null) {
      Action action = service.getAction("SetMute");
      ActionInvocation invocation = new ActionInvocation(action);
      invocation.setInput("Channel", "Master");
      invocation.setInput("DesiredMute", Boolean.toString(mute));
      runAction(invocation);
    }
  }


  void runAction(ActionInvocation<RemoteService> action) {
    // Executes asynchronous in the background
    upnpService.getControlPoint().execute(new ActionCallback(action) {

      @Override
      public void success(ActionInvocation invocation) {
        assert invocation.getOutput().length == 0;
        Log.v(TAG, "Successfully called Sonos action!");
      }

      @Override
      public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
        Log.i(TAG, "Failed to call Sonos action: " + defaultMsg);
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
