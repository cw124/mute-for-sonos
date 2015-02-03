package uk.co.chriswiggins.muteforsonos;

import android.app.AlarmManager;
import android.app.PendingIntent;
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
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.android.FixedAndroidLogHandler;
import org.fourthline.cling.model.message.header.UDADeviceTypeHeader;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.DeviceIdentity;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.types.DeviceType;
import org.fourthline.cling.model.types.UDADeviceType;
import org.fourthline.cling.model.types.UDAServiceId;
import org.fourthline.cling.registry.DefaultRegistryListener;
import org.fourthline.cling.registry.Registry;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import uk.co.chriswiggins.muteforsonos.log.LogManager;


public class SonosService extends Service {

  private static final String TAG = "SonosService";

  private static final DeviceType SONOS_DEVICE_TYPE = new UDADeviceType("ZonePlayer");

  public static final String PAUSETEMPORARILY_ACTION = "uk.co.chriswiggins.sonoscontrol.pausetemporarily";
  public static final String UNMUTE_ACTION = "uk.co.chriswiggins.sonoscontrol.unmute";
  private static final long MUTE_LENGTH = 30 * 1000L;
  private static final long MAX_MUTE_LENGTH = (9*60 + 59) * 1000L;
  private static final long DEFAULT_RETRY_DISCOVERY_DELAY = 1 * 1000L;
  private static final long MAX_RETRY_DISCOVERY_DELAY = 30 * 60 * 1000L;

  private LogManager logManager;

  private Handler handler;
  private AndroidUpnpService upnpService;

  private AlarmManager alarmManager;
  private PendingIntent unmuteIntent;

  private Map<DeviceIdentity, Sonos> sonoses = new ConcurrentHashMap<DeviceIdentity, Sonos>(16, 0.75f, 1);
  private Object muteLock = new Object();
  private boolean muted = false;
  private boolean wifiConnected = false;
  private String ssid;

  private ScheduledThreadPoolExecutor executor;
  private ScheduledFuture<?> tickerFuture;

  private long unmuteTime;

  private long retryDiscoveryDelay = DEFAULT_RETRY_DISCOVERY_DELAY;
  private Object retryDiscovery = new Object();
  private boolean retryScheduled = false;
  private long lastRetryStart = 0L;

  private Map<String, SeenSonoses> seenSonoses = new ConcurrentHashMap<String, SeenSonoses>(16, 0.75f, 1);



  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }



  @Override
  public void onCreate() {
    super.onCreate();

    logManager = new LogManager(this);
    logManager.startLogging();
    logManager.showNotification();

    executor = new ScheduledThreadPoolExecutor(1);

    // Terminate all thread pool threads after a while so we don't use
    // resources. We can afford to wait for them to be started up again.
    executor.setKeepAliveTime(45, TimeUnit.SECONDS);
    executor.allowCoreThreadTimeOut(true);

    handler = new Handler();
    alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

    // Make Cling log as needed.
    org.seamless.util.logging.LoggingUtil.resetRootHandler(
      new FixedAndroidLogHandler()
    );
    Logger.getLogger("org.fourthline.cling").setLevel(Level.INFO);

    // Bind to the UPnP service, creating it if necessary. By using bindService
    // (rather than startService) we get a reference to the service, sent back
    // via the ServiceConnection object.
    Log.v(TAG, "Binding to AndroidUpnpService...");
    getApplicationContext().bindService(
            new Intent(this, SonosUpnpService.class),
            new UpnpServiceConnection(),
            Context.BIND_AUTO_CREATE);

    // Register a broadcast receiver for finding out what's going on with wi-fi.
    registerReceiver(
            new WiFiBroadcastReceiver(),
            new IntentFilter("android.net.wifi.STATE_CHANGE"));

    registerReceiver(new SonosBroadcastReceiver(), new IntentFilter(UNMUTE_ACTION));
  }



  @Override
  public void onDestroy() {
    Log.i(TAG, "onDestroy");

    // Stop any future jobs that are scheduled to run, and shutdown the executor.
    executor.shutdownNow();

    logManager.shutdown();

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
   * Receives broadcasts, specifically the alarm intent telling us it's time
   * to unmute.
   */
  private class SonosBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      String action = intent.getAction();
      Log.d(TAG, "onReceive. Action = " + action);

      if (action.equals(UNMUTE_ACTION)) {
        unmute();
      }
    }
  }


  /**
   * Unmute. Called via AlarmManager and via the update UI tick, to deal with
   * the weird inaccuracy of AlarmManager.
   */
  private void unmute() {
    synchronized (muteLock) {

      Log.i(TAG, "Time = " + SystemClock.elapsedRealtime() + ". Unmute time = " + unmuteTime + ". Diff = " + (SystemClock.elapsedRealtime() - unmuteTime) / 1000.0f + "s");

      for (Sonos sonos : sonoses.values()) {
        Log.i(TAG, "Restoring state of " + sonos.getName());
        sonos.restoreMute();
      }

      muted = false;

      if (tickerFuture != null) {
        tickerFuture.cancel(false);
      }
      SonosWidgetProvider.notifyChange(SonosService.this);

      // There's a race condition where the user presses the button again as
      // this alarm triggers. The main thread grabs the lock and we block.
      // The main thread cancels the alarm (that's already started
      // to run) and schedules a new one a bit further in the future. We'll
      // then run regardless, so we'd better cancel that future extra run,
      // if it exists.

      alarmManager.cancel(unmuteIntent);
    }
  }


  /**
   * Regardless of where it comes from, process the message.
   */
  private void processIntent(Intent intent) {
    String action = intent.getAction();
    Log.i(TAG, "processIntent: Current state: " + getCurrentState());

    if (SonosService.PAUSETEMPORARILY_ACTION.equals(action)) {
      Log.d(TAG, "Doing muting stuff");

      synchronized (muteLock) {

        if (!wifiConnected) {
          Log.i(TAG, "No wi-fi, inform user...");
          handler.post(new Runnable() {
            @Override
            public void run() {
              Toast.makeText(SonosService.this, "Not connected to wi-fi", Toast.LENGTH_SHORT).show();
            }
          });

        } else if (sonoses.isEmpty()) {
          Log.i(TAG, "Wi-fi connected, but no Sonoses found. Inform user...");
          handler.post(new Runnable() {
            @Override
            public void run() {
              Toast.makeText(SonosService.this, "No Sonos systems found", Toast.LENGTH_SHORT).show();
            }
          });

        } else {

          if (!muted) {
            Log.i(TAG, "Not currently muted. Muting...");

            // Mute all Sonos systems.

            for (Sonos sonos : sonoses.values()) {
              Log.i(TAG, "Setting muted on " + sonos.getName());
              sonos.mute();
            }

            muted = true;

            // Schedule a regular job to update the UI with time left until
            // unmute.

            unmuteTime = SystemClock.elapsedRealtime() + MUTE_LENGTH;
            tickerFuture = executor.scheduleAtFixedRate(new UpdateUI(), 1000L, 1000L, TimeUnit.MILLISECONDS);

          } else {
            Log.i(TAG, "Already muted. Adding more mute.");

            unmuteTime = Math.min(unmuteTime + MUTE_LENGTH, SystemClock.elapsedRealtime() + MAX_MUTE_LENGTH);

            // Cancel current unmute future event. A new one at the correct time
            // will be added below.
            alarmManager.cancel(unmuteIntent);
          }

          // Set up an alarm to unmute (use an alarm so we are always woken up
          // to unmute, even if the phone is in deep sleep).
          unmuteIntent = PendingIntent.getBroadcast(this, 0, new Intent(UNMUTE_ACTION), 0);
          alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, unmuteTime, unmuteIntent);

          Log.i(TAG, "Time = " + SystemClock.elapsedRealtime() + ". Unmute time = " + unmuteTime + ". Diff = " + (SystemClock.elapsedRealtime() - unmuteTime) / 1000.0f + "s");
        }
      }
    }

    // Regardless of the intent, update the UI. This could be a 2nd (or more)
    // widget being added, so it needs a wrap around call to get it up to date.
    SonosWidgetProvider.notifyChange(SonosService.this);
  }



  /**
   * Gets the status of the system (for logging purposes).
   */
  public String getCurrentState() {
    if (!wifiConnected) {
      return "No wi-fi";
    } else if (sonoses.isEmpty()) {
      return "No Sonos systems found";
    } else if (!muted) {
      return "Found " + sonoses.size() + " Sonos systems";
    } else {
      return "Muted. Seconds until unmute: " + Math.round(Math.max(unmuteTime - SystemClock.elapsedRealtime(), 0L) / 1000.0f);
    }
  }



  public boolean isWifiConnected() {
    return wifiConnected;
  }



  public int getSecondsUntilUnmute() {
    return Math.round(Math.max(unmuteTime - SystemClock.elapsedRealtime(), 0L) / 1000.0f);
  }



  public int getNumKnownSonosSystems() {
    return sonoses.size();
  }



  public boolean isMuted() {
    return muted;
  }


  /**
   * Runs every second to update the UI.
   */
  class UpdateUI implements Runnable {
    public void run() {
      SonosWidgetProvider.notifyChange(SonosService.this);
      if (SystemClock.elapsedRealtime() - unmuteTime > 0L) {
        Log.i(TAG, "Forcing an unmute in case AlarmManager is late (which it often is for some reason)");
        unmute();
      }
    }
  }


  /**
   * Runnable that will retry discovery of devices on the network. Used when
   * Sonos discovery fails for whatever reason.
   */
  class RetryFailedDeviceDiscovery implements Runnable {
    public void run() {
      synchronized (retryDiscovery) {
        SeenSonoses seen = seenSonoses.get(ssid);
        if (seen != null) {
          if (sonoses.size() == seen.getMax(ssid)) {
            Log.i(TAG, "Would have retried discovery but seems like we've now found all expected Sonoses");
            retryScheduled = false;

          } else {
            Log.i(TAG, "Searching again for Sonos systems because we haven't found all we expected to...");

            if (upnpService != null) {
              upnpService.getControlPoint().search(new UDADeviceTypeHeader(SONOS_DEVICE_TYPE));
            }

            scheduleRetry();
          }
        }
      }
    }
  }


  private void scheduleRetry() {
    Log.i(TAG, "Scheduling retry for " + retryDiscoveryDelay/1000 + " seconds' time");
    executor.schedule(new RetryFailedDeviceDiscovery(), retryDiscoveryDelay, TimeUnit.MILLISECONDS);

    retryDiscoveryDelay *= 2;
    if (retryDiscoveryDelay > MAX_RETRY_DISCOVERY_DELAY) {
      retryDiscoveryDelay = MAX_RETRY_DISCOVERY_DELAY;
    }
  }


  /**
   * Runnable that will retry discovery of devices on the network. Used to
   * search several times when wi-fi becomes connected so we can be sure to
   * find everything.
   */
  class RetryDeviceDiscovery implements Runnable {
    public void run() {
      Log.i(TAG, "Searching again for Sonos systems...");
      if (upnpService != null) {
        upnpService.getControlPoint().search(new UDADeviceTypeHeader(SONOS_DEVICE_TYPE));
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
            WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            ssid = wifiManager.getConnectionInfo().getSSID();
            wifiConnected = true;

            if (!seenSonoses.containsKey(ssid)) {
              seenSonoses.put(ssid, new SeenSonoses());
            }

            Log.i(TAG, "Wi-fi connected (previously connected = " + wifiConnected +
                "). ssid = " + ssid + ". Will schedule device searches.");


            SonosWidgetProvider.notifyChange(SonosService.this);

            // HACK: Cling doesn't always seem to notice wi-fi has connected, or maybe it notices
            // but discovery fails anyway for some reason. Schedule a few manual searches in a bit
            // to try to ensure we find everything.
            executor.schedule(new RetryDeviceDiscovery(), 2*1000L, TimeUnit.MILLISECONDS);
            executor.schedule(new RetryDeviceDiscovery(), 5*1000L, TimeUnit.MILLISECONDS);
            executor.schedule(new RetryDeviceDiscovery(), 10*1000L, TimeUnit.MILLISECONDS);

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

      Log.i(TAG, "UPnP service connected.");

      upnpService = (AndroidUpnpService) service;

      // Get currently known devices.
      Collection<Device> devices = upnpService.getRegistry().getDevices(SONOS_DEVICE_TYPE);
      Log.d(TAG, "Registry already knows about " + devices.size() + " Sonos devices. Adding them.");
      for (Device device : devices) {
        registryListener.deviceAdded(device);
      }

      // Listen for devices that appear in the future.
      upnpService.getRegistry().addListener(registryListener);

      // Search for Sonos devices not yet known about.
      Log.d(TAG, "Searching for Sonos devices on the network...");
      upnpService.getControlPoint().search(new UDADeviceTypeHeader(SONOS_DEVICE_TYPE));
    }


    public void onServiceDisconnected(ComponentName className) {
      Log.i(TAG, "UPnP disconnected. Clearing references to Sonos systems.");
      sonoses.clear();
      upnpService = null;
    }
  };



  /**
   * The various methods on this class are invoked by the UPnP service as
   * devices come and go on the network.
   */
  class BrowseRegistryListener extends DefaultRegistryListener {

    @Override
    public void remoteDeviceDiscoveryFailed(Registry registry, final RemoteDevice device, final Exception ex) {
      Log.w(TAG, "Discovery failed of '" + device.getDisplayString() + "': "
              + (ex != null ? ex.toString() : "Couldn't retrieve device/service descriptors"));

      Log.w(TAG, "Friendly name: " + device.getDetails().getFriendlyName());
      Log.w(TAG, "Hydrated: " + device.isFullyHydrated());

      if (device.getType().equals(SONOS_DEVICE_TYPE)) {
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

    public void deviceAdded(final Device device) {
      if (device.isFullyHydrated()) {
        Log.d(TAG, "Found device: " +
                device.getIdentity().getUdn() + ": " +
                device.getDisplayString());

        if (device.getType().equals(SONOS_DEVICE_TYPE) && device.findService(new UDAServiceId("RenderingControl")) != null) {
          Log.i(TAG, "Found a Sonos system.");

          if (upnpService != null) {
            Sonos sonos = new Sonos(device.getDetails().getFriendlyName(), upnpService, (RemoteDevice) device);
            sonoses.put(device.getIdentity(), sonos);

            if (!seenSonoses.containsKey(ssid)) {
              seenSonoses.put(ssid, new SeenSonoses());
            }
            seenSonoses.get(ssid).seen(sonoses.size(), ssid);

            SonosWidgetProvider.notifyChange(SonosService.this);
          }
        }
      }
    }

    public void deviceRemoved(final Device device) {
      Log.i(TAG, "Device removed: "
              + (device.isFullyHydrated() ? device.getDisplayString() : device.getDisplayString() + " *"));

      Sonos sonos = sonoses.remove(device.getIdentity());

      if (sonos != null) {
        Log.i(TAG, "Removed Sonos system: " + device.getDetails().getFriendlyName());
        SonosWidgetProvider.notifyChange(SonosService.this);

        SeenSonoses seen = seenSonoses.get(ssid);
        if (seen != null && sonoses.size() < seen.getMax(ssid)) {
          Log.i(TAG, "Fewer than maximum expected Sonoses for " + ssid + ". Will do a search");

          synchronized (retryDiscovery) {
            if (retryScheduled) {
              Log.i(TAG, "Retry already scheduled, will just wait for that one...");

            } else {
              Log.i(TAG, "Retry not currently scheduled, will schedule one");

              // TODO: do something with lastRetryStart
              retryDiscoveryDelay = DEFAULT_RETRY_DISCOVERY_DELAY;

              scheduleRetry();
              retryScheduled = true;
            }
          }

        }
      }

    }
  }


  /**
   * Tracks how many Sonoses we've seen on a given network, and how long ago
   * we last saw each high watermark. Maximums eventually timeout and fall
   * back to previous maximums so we can accommodate real changes to how many
   * systems exist.
   */
  private static class SeenSonoses {

    private static final long TIMEOUT = 24 * 60 * 60 * 1000L;

    private int maxNumSonosesSeen = 0;

    // Maps number of Sonoses seen to when at least that many was last seen.
    private Map<Integer, Long> lastSeen = new ConcurrentHashMap<Integer, Long>(16, 0.75f, 1);


    /**
     * Records that the given number of Sonoses have been seen on the given
     * network.
     */
    public void seen(int num, String ssid) {
      Log.d(TAG, "Recording that we've seen " + num + " Sonses on " + ssid);

      if (num > maxNumSonosesSeen) {
        maxNumSonosesSeen = num;
        Log.i(TAG, "New maximum Sonoses seen for " + ssid + ": " + num);
      }

      // Update last seen times for all counts less than or equal to the
      // number given.
      while (num > 0) {
        lastSeen.put(new Integer(num), SystemClock.elapsedRealtime());
        num--;
      }
    }


    /**
     * Gets the maximum number of Sonoses seen on the given network.
     */
    public int getMax(String ssid) {

      // Process any timeouts.

      while (maxNumSonosesSeen > 0 &&
          SystemClock.elapsedRealtime() - lastSeen.get(maxNumSonosesSeen) > TIMEOUT) {

        lastSeen.remove(maxNumSonosesSeen);
        maxNumSonosesSeen--;

        Log.i(TAG, "Not seen " + (maxNumSonosesSeen+1) + " Sonoses on " + ssid +
            " for a long time. Max now " + maxNumSonosesSeen);
      }

      return maxNumSonosesSeen;
    }
  }

}
