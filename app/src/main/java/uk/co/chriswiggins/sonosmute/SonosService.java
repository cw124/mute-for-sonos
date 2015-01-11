package uk.co.chriswiggins.sonosmute;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.Parcelable;
import android.util.Log;

import org.teleal.cling.android.AndroidUpnpService;
import org.teleal.cling.android.AndroidUpnpServiceImpl;
import org.teleal.cling.model.meta.Device;
import org.teleal.cling.model.meta.LocalDevice;
import org.teleal.cling.model.meta.RemoteDevice;
import org.teleal.cling.registry.DefaultRegistryListener;
import org.teleal.cling.registry.Registry;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;



public class SonosService extends Service {

  private static final String TAG = "SonosService";

  public static final String PAUSETEMPORARILY_ACTION = "uk.co.chriswiggins.sonoscontrol.pausetemporarily";
  private static final long MUTE_LENGTH = 10 * 1000L;

  private AndroidUpnpService upnpService;

  private Map<String, Sonos> sonoses = new ConcurrentHashMap<String, Sonos>();
  private Map<Sonos, Boolean> previousMuteStates = new HashMap<Sonos, Boolean>();
  private boolean wifiConnected = false;
  private ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2);
  private ScheduledFuture<?> tickerFuture;
  private ScheduledFuture<?> unmuteFuture;
  private long unmuteTime;



  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }



  @Override
  public void onCreate() {
    super.onCreate();

    // Bind to the UPnP service, creating it if necessary. By using bindService
    // (rather than startService) we get a reference to the service, sent back
    // via the ServiceConnection object.
    Log.v(TAG, "Binding to AndroidUpnpService...");
    getApplicationContext().bindService(
            new Intent(this, AndroidUpnpServiceImpl.class),
            new UpnpServiceConnection(),
            Context.BIND_AUTO_CREATE);

    // Register a broadcast receiver for finding out what's going on with wi-fi.
    registerReceiver(
            new WiFiBroadcastReceiver(),
            new IntentFilter("android.net.wifi.STATE_CHANGE"));
  }



  @Override
  public void onDestroy() {
    Log.i(TAG, "onDestroy");
    super.onDestroy();
  }



  /**
   * Where all the messages come in from people pressing buttons.
   */
  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    Log.i(TAG, "onStartCommand");

    if (intent != null) {
      processIntent(intent);
    }

    // Start sticky so we keep running (need to keep looking out for Sonos
    // systems on the network so we can access them quickly when asked to.
    return START_STICKY;
  }



  /**
   * Regardless of where it comes from, process the message.
   */
  private void processIntent(Intent intent) {
    String action = intent.getAction();
    String cmd = intent.getStringExtra("command");
    Log.i(TAG, "Process intent: action = " + action + ", cmd = " + cmd);
    Log.i(TAG, "Current state: " + getCurrentState());

    if (SonosService.PAUSETEMPORARILY_ACTION.equals(action)) {
      Log.d(TAG, "Doing muting stuff");

      synchronized (previousMuteStates) {

        if (previousMuteStates.isEmpty()) {
          Log.i(TAG, "Not currently muted. Muting...");

          // Capture the current mute state of all Sonos systems, so we can
          // restore it when we unmute.

          for (Sonos sonos : sonoses.values()) {
            boolean muted = sonos.isMuted();
            Log.i(TAG, "Muted state of " + sonos.getName() + " is " + muted);
            previousMuteStates.put(sonos, muted);
          }

          // Mute all Sonos systems.

          for (Sonos sonos : sonoses.values()) {
            Log.i(TAG, "Setting muted on " + sonos.getName());
            sonos.mute(true);
          }

          // Schedule a regular job to update the UI with time left until
          // unmute.

          unmuteTime = System.currentTimeMillis() + MUTE_LENGTH;
          tickerFuture = executor.scheduleAtFixedRate(new UpdateUI(), 1000L, 1000L, TimeUnit.MILLISECONDS);

        } else {
          Log.i(TAG, "Already muted. Adding more mute.");

          unmuteTime += MUTE_LENGTH;

          // Cancel current unmute future event. A new one at the correct time
          // will be added below.
          unmuteFuture.cancel(false);
        }

        // Set up a future job to unmute.
        unmuteFuture = executor.schedule(new Unmute(), unmuteTime - System.currentTimeMillis(), TimeUnit.MILLISECONDS);

        // Update the UI right now.
        SonosWidgetProvider.notifyChange(SonosService.this);

        Log.i(TAG, "unmute = " + unmuteTime + " current = " + System.currentTimeMillis() + " left = " + Math.round(Math.max(unmuteTime - System.currentTimeMillis(), 0L) / 1000.0f) + " id = " + System.identityHashCode(this));
      }
    }
  }



  /**
   * Gets the status of the system (for logging purposes).
   */
  public String getCurrentState() {
    if (!wifiConnected) {
      return "No wi-fi";
    } else if (sonoses.isEmpty()) {
      return "No Sonos systems found";
    } else if (previousMuteStates.isEmpty()) {
      return "Found " + sonoses.size() + " Sonos systems";
    } else {
      return "Muted. Seconds until unmute: " + Math.round(Math.max(unmuteTime - System.currentTimeMillis(), 0L) / 1000.0f);
    }
  }



  public boolean isWifiConnected() {
    return wifiConnected;
  }



  public int getSecondsUntilUnmute() {
    return Math.round(Math.max(unmuteTime - System.currentTimeMillis(), 0L) / 1000.0f);
  }



  public int getNumKnownSonosSystems() {
    return sonoses.size();
  }



  public boolean isMuted() {
    return !previousMuteStates.isEmpty();
  }


  /**
   * Runs every second to update the UI.
   */
  class UpdateUI implements Runnable {
    public void run() {
      SonosWidgetProvider.notifyChange(SonosService.this);
    }
  }


  /**
   * Runnable that will unmute the Sonos systems after a delay.
   */
  class Unmute implements Runnable {

    public void run() {
      synchronized (previousMuteStates) {

        Log.i(TAG, "Current state: " + getCurrentState());

        // Need to check it is actually time to unmute, in case the user
        // pressed the button again as we were called. Another delayed call
        // to this runnable will already have been set up.

        if (System.currentTimeMillis() < unmuteTime + 100L) {

          for (Map.Entry<Sonos, Boolean> entry : previousMuteStates.entrySet()) {
            Sonos sonos = entry.getKey();
            boolean muted = entry.getValue();
            Log.i(TAG, "Restoring state of " + sonos.getName() + " to " + muted);
            sonos.mute(muted);
          }

          previousMuteStates.clear();
          tickerFuture.cancel(false);
          SonosWidgetProvider.notifyChange(SonosService.this);
        }
      }
    }
  }



  /**
   * BroadcaseReceiver for monitoring the status of the wi-fi connection.
   */
  private class WiFiBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      Log.v(TAG, "Got wi-fi intent: " + intent);

      Parcelable extra = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);

      if (extra != null && extra instanceof NetworkInfo) {
        NetworkInfo networkInfo = (NetworkInfo) extra;

        if (networkInfo.getType() == ConnectivityManager.TYPE_WIFI) {

          if (networkInfo.getState() == NetworkInfo.State.CONNECTED) {
            Log.d(TAG, "Wi-fi connected.");
            wifiConnected = true;
            SonosWidgetProvider.notifyChange(SonosService.this);

          } else {
            Log.d(TAG, "Wi-fi not connected.");
            wifiConnected = false;
            SonosWidgetProvider.notifyChange(SonosService.this);
          }
        }
      }
    }
  }



  /**
   * Our connection to the UPnP service. Listens for devices on the network as
   * they come and go and keeps track of them.
   */
  private class UpnpServiceConnection implements ServiceConnection {

    private BrowseRegistryListener registryListener = new BrowseRegistryListener();

    public void onServiceConnected(ComponentName className, IBinder service) {

      Log.i(TAG, "UPnP service connected. Will search for devices to try to find Sonos.");

      upnpService = (AndroidUpnpService) service;

      // Refresh the list with all known devices.
      for (Device device : upnpService.getRegistry().getDevices()) {
        registryListener.deviceAdded(device);
      }

      // Getting ready for future device advertisements.
      upnpService.getRegistry().addListener(registryListener);

      // Search asynchronously for all devices.
      upnpService.getControlPoint().search();
    }


    public void onServiceDisconnected(ComponentName className) {
      Log.i(TAG, "UPnP disconnected. Clearing reference to Sonos.");
      upnpService = null;
    }
  };



  /**
   * The various methods on this class are invoked by the UPnP service as
   * devices come and go on the network.
   */
  class BrowseRegistryListener extends DefaultRegistryListener {

    @Override
    public void remoteDeviceDiscoveryStarted(Registry registry, RemoteDevice device) {
      deviceAdded(device);
    }

    @Override
    public void remoteDeviceDiscoveryFailed(Registry registry, final RemoteDevice device, final Exception ex) {
      Log.w(TAG, "Discovery failed of '" + device.getDisplayString() + "': "
              + (ex != null ? ex.toString() : "Couldn't retrieve device/service descriptors"));

      Log.w(TAG, "Friendly name: " + device.getDetails().getFriendlyName());
      Log.w(TAG, "Hydrated: " + device.isFullyHydrated());

      if (device.getDetails().getFriendlyName().contains("Sonos")) {
        Log.w(TAG, "Failed discovery was of a Sonos system.");
      }

      deviceRemoved(device);
    }

    @Override
    public void remoteDeviceAdded(Registry registry, RemoteDevice device) {
      deviceAdded(device);
    }

    @Override
    public void remoteDeviceRemoved(Registry registry, RemoteDevice device) {
      deviceRemoved(device);
    }

    @Override
    public void localDeviceAdded(Registry registry, LocalDevice device) {
      deviceAdded(device);
    }

    @Override
    public void localDeviceRemoved(Registry registry, LocalDevice device) {
      deviceRemoved(device);
    }

    public void deviceAdded(final Device device) {
      if (device.isFullyHydrated()) {
        Log.d(TAG, "Found device: " + device.getIdentity().getUdn().getIdentifierString() + ": " + device.getDisplayString());

        if (device.getDetails().getFriendlyName().contains("Sonos")) {
          Log.i(TAG, "Found a Sonos system.");

          Sonos sonos = new Sonos(device.getDetails().getFriendlyName(), new AndroidControlPointProvider(upnpService), (RemoteDevice) device);

          sonoses.put(device.getIdentity().getUdn().getIdentifierString(), sonos);

          SonosWidgetProvider.notifyChange(SonosService.this);
        }
      }
    }

    public void deviceRemoved(final Device device) {
      Log.i(TAG, "Device removed: "
              + (device.isFullyHydrated() ? device.getDisplayString() : device.getDisplayString() + " *"));

      if (device.isFullyHydrated()) {
        if (sonoses.remove(device.getIdentity().getUdn().getIdentifierString()) != null) {
          Log.i(TAG, "Removing Sonos system: " + device.getDetails().getFriendlyName());
          SonosWidgetProvider.notifyChange(SonosService.this);
        }
      }

    }
  }

}
