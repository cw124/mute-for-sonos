package uk.co.chriswiggins.sonosmute;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.teleal.cling.UpnpService;
import org.teleal.cling.UpnpServiceImpl;
import org.teleal.cling.controlpoint.ActionCallback;
import org.teleal.cling.controlpoint.SubscriptionCallback;
import org.teleal.cling.model.action.ActionInvocation;
import org.teleal.cling.model.gena.CancelReason;
import org.teleal.cling.model.gena.GENASubscription;
import org.teleal.cling.model.message.UpnpResponse;
import org.teleal.cling.model.message.header.STAllHeader;
import org.teleal.cling.model.meta.Action;
import org.teleal.cling.model.meta.RemoteDevice;
import org.teleal.cling.model.meta.RemoteService;
import org.teleal.cling.model.meta.Service;
import org.teleal.cling.model.state.StateVariableValue;
import org.teleal.cling.model.types.UDAServiceId;
import org.teleal.cling.registry.DefaultRegistryListener;
import org.teleal.cling.registry.Registry;
import org.teleal.common.logging.LoggingUtil;
import org.xml.sax.SAXException;

import android.util.Log;


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
    Action action = service.getAction("SetMute");
    ActionInvocation invocation = new ActionInvocation(action);
    invocation.setInput("Channel", "Master");
    invocation.setInput("DesiredMute", Boolean.toString(mute));
    runAction(invocation);
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


  public static void main(String[] args) {

    // Make java.util.logging output something vaguely nice.

    ConsoleHandler consoleHandler = new ConsoleHandler();
    consoleHandler.setLevel(Level.ALL);

    consoleHandler.setFormatter(new Formatter() {
      @Override
      public String format(LogRecord record) {
        StringBuffer buf = new StringBuffer(180);
        DateFormat dateFormat = new SimpleDateFormat("kk:mm:ss");

        buf.append(String.format("%-7s", record.getLevel().toString()));
        buf.append(" ");
        buf.append(String.format("%-8s", dateFormat.format(new Date(record.getMillis()))));
        buf.append(": ");
        buf.append(String.format("%-15.15s", record.getLoggerName()));
        buf.append(": ");
        buf.append(formatMessage(record));

        buf.append("\n");

        Throwable throwable = record.getThrown();
        if (throwable != null) {
          StringWriter sink = new StringWriter();
          throwable.printStackTrace(new PrintWriter(sink, true));
          buf.append(sink.toString());
        }

        return buf.toString();
      }

    });

    LoggingUtil.resetRootHandler(consoleHandler);

    // Change this to affect what our code logs.
    Logger.getLogger("").setLevel(Level.ALL);

    new SonosDriver();
  }


  private static class SonosDriver {

    private RemoteDevice rd;

    public SonosDriver() {
      UpnpService upnpService = new UpnpServiceImpl();

      // Add a listener for device registration events
      upnpService.getRegistry().addListener(new DefaultRegistryListener() {

        @Override
        public void remoteDeviceAdded(Registry registry, RemoteDevice device) {
          // TODO: What's the proper way to determine this is the Sonos system?
          Log.i(TAG, "Found remote device: " + device.getDetails().getFriendlyName());
          if (device.getDetails().getFriendlyName().contains("Sonos")) {
            rd = device;
            synchronized (SonosDriver.this) {
              Log.i(TAG, "Notifying");
              SonosDriver.this.notifyAll();
            }
          }
        }

      });

      // Broadcast a search message for all devices
      upnpService.getControlPoint().search(new STAllHeader());

      Log.i(TAG, "Searching...");
      synchronized (this) {
        try {
          this.wait();
        } catch (InterruptedException e) {
        }
      }

      Log.i(TAG, "Found!");

      final Sonos sonos = new Sonos("Foo", new StandardControlPointProvider(upnpService), rd);

      sonos.addOneOffCallbackAfterChange(new SonosCallback() {
        public void callback(Sonos sonos) {
          Log.i(TAG, "Got initial setup");
          sonos.addOneOffCallbackAfterChange(new SonosCallback() {
            public void callback(Sonos sonos) {
              Log.i(TAG, "Got initial setup 2");
              sonos.addOneOffCallbackAfterChange(new SonosCallback() {
                public void callback(Sonos sonos) {
                  Log.i(TAG, "Something changed!");
                }
              });
            }
          });
        }
      });
    }
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
    return this.sonosDevice.getIdentity().getUdn().getIdentifierString().equals(that.sonosDevice.getIdentity().getUdn().getIdentifierString());
  }


  @Override
  public int hashCode() {
    return this.sonosDevice.getIdentity().getUdn().getIdentifierString().hashCode();
  }
}
