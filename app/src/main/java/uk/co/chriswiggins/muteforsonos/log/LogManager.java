package uk.co.chriswiggins.muteforsonos.log;


import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import uk.co.chriswiggins.muteforsonos.R;


/**
 * Provides a method to show a notification that launches an activity that
 * shows the app's log file. Runs logcat in a separate process in order to
 * be able to do this.
 */
public class LogManager {

  private static final String TAG = "LogManager";
  public static final String LOG_FILE = "uk.co.chriswiggins.muteforsonos.LogManager.LOG_FILE";

  private Context context;
  private String logFile;
  private Process process;


  public LogManager(Context context) {
    this.context = context;
  }


  public void startLogging() {
    try {
      File cacheDir = context.getCacheDir();
      String prefix = new SimpleDateFormat("yyyy-MM-dd_").format(new Date());
      logFile = File.createTempFile(prefix, ".log", cacheDir).getAbsolutePath();

      process = Runtime.getRuntime().exec(
              "logcat -f " + logFile +
              " -v time SonosService:I SonosWidgetProvider:I Sonos:I LogManager:I *:S");

      Log.i(TAG, "Writing log to " + logFile + "...");

    } catch (IOException e) {
      Log.w(TAG, "Could not start logging", e);
    }
  }


  public void showNotification() {
    Log.i(TAG, "Showing log notification");

    NotificationCompat.Builder builder =
            new NotificationCompat.Builder(context)
                    .setSmallIcon(R.drawable.ic_bubble)
                    .setContentTitle("Mute for Sonos")
                    .setContentText("View logs...");

    Intent intent = new Intent(context, ShowLogActivity.class);
    intent.putExtra(LOG_FILE, logFile);

    TaskStackBuilder stackBuilder = TaskStackBuilder.create(context);
    stackBuilder.addParentStack(ShowLogActivity.class);
    stackBuilder.addNextIntent(intent);
    PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT);

    builder.setContentIntent(resultPendingIntent);

    NotificationManager notificationManager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

    notificationManager.notify(1, builder.build());
  }


  public void shutdown() {
    Log.i(TAG, "Log manager shutting down. Killing logcat process");

    if (process != null) {
      process.destroy();
    }
  }
}
