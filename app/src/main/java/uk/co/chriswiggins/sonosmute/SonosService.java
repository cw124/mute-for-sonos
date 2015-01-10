package uk.co.chriswiggins.sonosmute;

import android.app.Service;
import android.appwidget.AppWidgetManager;
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

import org.teleal.cling.android.AndroidUpnpService;
import org.teleal.cling.android.AndroidUpnpServiceImpl;
import org.teleal.cling.model.meta.Device;
import org.teleal.cling.model.meta.LocalDevice;
import org.teleal.cling.model.meta.RemoteDevice;
import org.teleal.cling.registry.DefaultRegistryListener;
import org.teleal.cling.registry.Registry;
import org.teleal.common.logging.LoggingUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;



public class SonosService extends Service {

  private static Logger logger = Logger.getLogger(SonosService.class.getCanonicalName());

  public static final String SERVICECMD = "uk.co.chriswiggins.sonomutecommand";
  public static final String CMDNAME = "command";
  public static final String PLAYSTATE_CHANGED = "uk.co.chriswiggins.sonoscontrol.playstatechanged";
  public static final String PAUSETEMPORARILY_ACTION = "uk.co.chrisiwggins.sonoscontrol.pausetemporarily";

  /**
   * Used to communicate what change has happened to the widget.
   */
  public static enum Change {
    MUTED, UNMUTED, TICK, WIFI_CONNECTED, WIFI_DISCONNECTED, SONOS_ADDED, SONOS_REMOVED
  }

  private AndroidUpnpService upnpService;
  private ServiceConnection serviceConnection = new SonosServiceConnection();
  private BrowseRegistryListener registryListener = new BrowseRegistryListener();

  private SonosWidgetProvider sonosWidgetProvider = SonosWidgetProvider.getInstance();

  private Map<String, Sonos> sonoses = new ConcurrentHashMap<String, Sonos>();
  private Map<Sonos, Boolean> previousMuteStates = new HashMap<Sonos, Boolean>();
  private boolean wifiConnected = false;

  private ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2);
  private ScheduledFuture<?> tickerFuture;
  private ScheduledFuture<?> unmuteFuture;

  private long muteLength = 10 * 1000L;
  private long unmuteTime ;




  public SonosService() {
    logger.info("Constructor. Instance = " + System.identityHashCode(this));

    // Sort out logging.
    Handler handler = new FixedAndroidHandler();
    handler.setLevel(Level.ALL);
    LoggingUtil.resetRootHandler(handler);
    Logger.getLogger("").setLevel(Level.INFO);
    Logger.getLogger("uk.co.chriswiggins.sonosmute").setLevel(Level.ALL);

    logger.info("Logging initialised");
    logger.fine("Fine level message");
    logger.finest("Finest level message");
    logger.info("Logging tests finished");
  }


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
    logger.finest("Binding to AndroidUpnpService...");
    getApplicationContext().bindService(
            new Intent(this, AndroidUpnpServiceImpl.class),
            serviceConnection,
            Context.BIND_AUTO_CREATE);

    // Register a broadcast receiver for finding out what's going on with wi-fi.
    registerReceiver(
            new WiFiBroadcastReceiver(),
            new IntentFilter("android.net.wifi.STATE_CHANGE"));

    IntentFilter commandFilter = new IntentFilter();
    commandFilter.addAction(SERVICECMD);
    commandFilter.addAction(PAUSETEMPORARILY_ACTION);
    registerReceiver(new SonosBroadcastReceiver(), commandFilter);
  }


  /**
   * Where all the messages come in from people pressing buttons. Not sure
   * why. See broadcast receiver below.
   */
  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    logger.info("onStartCommand");

    if (intent != null) {
      processIntent(intent);
    }

    // Start sticky so we keep running (need to keep looking out for Sonos
    // systems on the network so we can access them quickly when asked to.
    return START_STICKY;
  }


  /**
   * In theory receives broadcasts from SonosWidgetProveder when buttons are
   * clicked, but I don't think this ever actually gets called -- everything
   * comes through onStartCommand for some reason.
   */
  private class SonosBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      logger.info("SonosBroadcastReceiver: onReceive");
      processIntent(intent);
    }
  }


  /**
   * Regardless of where it comes from, process the message.
   */
  private void processIntent(Intent intent) {
    String action = intent.getAction();
    String cmd = intent.getStringExtra("command");
    logger.info("Process intent: action = " + action + ", cmd = " + cmd);
    logger.info("Current state:" + getCurrentState());

    if (SonosWidgetProvider.CMDAPPWIDGETUPDATE.equals(cmd)) {
      logger.fine("Doing wrap around thing");
      int[] appWidgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
      sonosWidgetProvider.performUpdate(SonosService.this, appWidgetIds);

    } else {
      logger.fine("Doing muting stuff");

      synchronized (previousMuteStates) {

        if (previousMuteStates.isEmpty()) {
          logger.info("Not currently muted. Muting...");

          // Capture the current mute state of all Sonos systems, so we can
          // restore it when we unmute.

          for (Sonos sonos : sonoses.values()) {
            boolean muted = sonos.isMuted();
            logger.info("Muted state of " + sonos.getName() + " is " + muted);
            previousMuteStates.put(sonos, muted);
          }

          // Mute all Sonos systems.

          for (Sonos sonos : sonoses.values()) {
            logger.info("Setting muted on " + sonos.getName());
            sonos.mute(true);
          }

          // Schedule a regular job to update the UI with time left until
          // unmute.

          unmuteTime = System.currentTimeMillis() + muteLength;
          tickerFuture = executor.scheduleAtFixedRate(new UpdateUI(), 1000L, 1000L, TimeUnit.MILLISECONDS);

        } else {
          logger.info("Already muted. Adding more mute.");

          unmuteTime += muteLength;

          // Cancel current unmute future event. A new one at the correct time
          // will be added below.
          unmuteFuture.cancel(false);
        }

        // Set up a future job to unmute.
        unmuteFuture = executor.schedule(new Unmute(), unmuteTime - System.currentTimeMillis(), TimeUnit.MILLISECONDS);

        // Update the UI right now.
        sonosWidgetProvider.notifyChange(SonosService.this, Change.MUTED);

        logger.info("unmute = " + unmuteTime + " current = " + System.currentTimeMillis() + " left = " + Math.round(Math.max(unmuteTime - System.currentTimeMillis(), 0L) / 1000.0f) + " id = " + System.identityHashCode(this));
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



  class UpdateUI implements Runnable {
    public void run() {
      sonosWidgetProvider.notifyChange(SonosService.this, Change.TICK);
    }
  }


  /**
   * Runnable that will unmute the Sonos systems after a delay.
   */
  class Unmute implements Runnable {

    public void run() {
      synchronized (previousMuteStates) {

        logger.info("Current state:" + getCurrentState());

        // Need to check it is actually time to unmute, in case the user
        // pressed the button again as we were called. Another delayed call
        // to this runnable will already have been set up.

        if (System.currentTimeMillis() < unmuteTime + 100L) {

          for (Map.Entry<Sonos, Boolean> entry : previousMuteStates.entrySet()) {
            Sonos sonos = entry.getKey();
            boolean muted = entry.getValue();
            logger.info("Restoring state of " + sonos.getName() + " to " + muted);
            sonos.mute(muted);
          }

          previousMuteStates.clear();
          tickerFuture.cancel(false);
          sonosWidgetProvider.notifyChange(SonosService.this, Change.UNMUTED);
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
      logger.finest("Got wi-fi intent: " + intent);

      Parcelable extra = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);

      if (extra != null && extra instanceof NetworkInfo) {
        NetworkInfo networkInfo = (NetworkInfo) extra;

        if (networkInfo.getType() == ConnectivityManager.TYPE_WIFI) {

          if (networkInfo.getState() == NetworkInfo.State.CONNECTED) {
            logger.fine("Wi-fi connected.");
            wifiConnected = true;
            sonosWidgetProvider.notifyChange(SonosService.this, Change.WIFI_CONNECTED);

          } else {
            logger.fine("Wi-fi not connected.");
            wifiConnected = false;
            sonosWidgetProvider.notifyChange(SonosService.this, Change.WIFI_DISCONNECTED);
          }
        }
      }
    }
  }



  /**
   * Our connection to the UPnP service. Listens for devices on the network as
   * they come and go and keeps track of them.
   */
  private class SonosServiceConnection implements ServiceConnection {

    public void onServiceConnected(ComponentName className, IBinder service) {

      logger.info("UPnP service connected. Will search for devices to try to find Sonos.");

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
      logger.info("UPnP disconnected. Clearing reference to Sonos.");
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
      logger.info("Discovery failed of '" + device.getDisplayString() + "': "
              + (ex != null ? ex.toString() : "Couldn't retrieve device/service descriptors"));
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
        logger.fine("Found device: " + device.getIdentity().getUdn().getIdentifierString() + ": " + device.getDisplayString());

        if (device.getDetails().getFriendlyName().contains("Sonos")) {
          logger.info("Found a Sonos system.");

          Sonos sonos = new Sonos(device.getDetails().getFriendlyName(), new AndroidControlPointProvider(upnpService), (RemoteDevice) device);

          sonoses.put(device.getIdentity().getUdn().getIdentifierString(), sonos);

          sonosWidgetProvider.notifyChange(SonosService.this, Change.SONOS_ADDED);
        }
      }
    }

    public void deviceRemoved(final Device device) {
      logger.info("Device removed: "
              + (device.isFullyHydrated() ? device.getDisplayString() : device.getDisplayString() + " *"));

      if (device.isFullyHydrated()) {
        if (sonoses.remove(device.getIdentity().getUdn().getIdentifierString()) != null) {
          logger.info("Removing Sonos system: " + device.getDetails().getFriendlyName());
          sonosWidgetProvider.notifyChange(SonosService.this, Change.SONOS_REMOVED);
        }
      }

    }
  }

}
