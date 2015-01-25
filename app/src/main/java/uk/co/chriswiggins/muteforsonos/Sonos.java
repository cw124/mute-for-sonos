package uk.co.chriswiggins.muteforsonos;

import android.util.Log;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/**
 * Represents and interfaces with a specific Sonos system on the network.
 * If you have multiple Sonos systems in your house, you will have multiple
 * instances of this class.
 */
public class Sonos {

  private static final String TAG = "Sonos";

  private String name;
  ControlPointProvider controlPointProvider;
  private RemoteDevice sonosDevice;

  private Boolean muted = null;

  private List<SonosCallback> callbackQueue = new ArrayList<SonosCallback>();
  private List<SonosCallback> oneOffCallbackQueue = new ArrayList<SonosCallback>();


  public Sonos(String name, ControlPointProvider controlPointProvider, RemoteDevice sonosDevice) {
    this.name = name;
    this.controlPointProvider = controlPointProvider;
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

          doCallbacks();
        }

      }

      public void eventsMissed(GENASubscription sub, int numberOfMissedEvents) {
        Log.i(TAG, "Missed events: " + numberOfMissedEvents);
      }

    };

    controlPointProvider.getControlPoint().execute(callback);

  }


  public String getName() {
    return name;
  }


  public interface SonosCallback {
    public void callback(Sonos sonos);
  }

  public void addCallbackAfterChange(SonosCallback callback) {
    synchronized (callbackQueue) {
      callbackQueue.add(callback);
    }
  }

  public void addOneOffCallbackAfterChange(SonosCallback callback) {
    synchronized (oneOffCallbackQueue) {
      oneOffCallbackQueue.add(callback);
    }
  }

  private void doCallbacks() {
    // Take a copy of the queues and empty the existing ones while holding
    // the lock, then iterate and call callbacks. This allows callbacks to
    // add further callbacks without modifying the queue we're iterating
    // over (if we just hold the lock and iterate and call then if callbacks
    // add a new callback on a different thread, we'll deadlock; if they add
    // a new callback on the same thread (more likely), we'll get a
    // concurrent modification exception).

    Log.v(TAG, "Calling callbacks");

    List<SonosCallback> callbackQueueCopy;
    synchronized (callbackQueue) {
      callbackQueueCopy = new ArrayList<SonosCallback>(callbackQueue);
    }

    for (SonosCallback callback : callbackQueueCopy) {
      callback.callback(this);
    }

    List<SonosCallback> oneOffCallbackQueueCopy;
    synchronized (oneOffCallbackQueue) {
      oneOffCallbackQueueCopy = oneOffCallbackQueue;
      oneOffCallbackQueue = new ArrayList<SonosCallback>();
    }

    for (SonosCallback callback : oneOffCallbackQueueCopy) {
      callback.callback(this);
    }
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
    controlPointProvider.getControlPoint().execute(new ActionCallback(action) {

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
