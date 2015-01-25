package uk.co.chriswiggins.muteforsonos;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;


public class SonosWidgetProvider extends AppWidgetProvider {

  private static final String TAG = "SonosWidgetProvider";

  static final ComponentName THIS_APPWIDGET = new ComponentName("uk.co.chriswiggins.muteforsonos",
                  "uk.co.chriswiggins.muteforsonos.SonosWidgetProvider");


  @Override
  public void onEnabled(Context context) {
    Log.i(TAG, "onEnabled");
  }


  @Override
  public void onDisabled(Context context) {
    Log.i(TAG, "onDisabled");
    context.stopService(new Intent(context.getApplicationContext(), SonosService.class));
  }


  @Override
  public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
    Log.i(TAG, "onUpdate");

    defaultAppWidget(context, appWidgetIds);

    Log.i(TAG, "Starting SonosService");
    context.startService(new Intent(context.getApplicationContext(), SonosService.class));
  }


  /**
   * Initialize given widgets to default state, where we launch Music on default click
   * and hide actions if service not running.
   */
  private void defaultAppWidget(Context context, int[] appWidgetIds) {
    final Resources res = context.getResources();
    final RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.sonos_widget);

    linkButtons(context, views);
    pushUpdate(context, appWidgetIds, views);
  }


  private static void pushUpdate(Context context, int[] appWidgetIds, RemoteViews views) {
    // Update specific list of appWidgetIds if given, otherwise default to all
    final AppWidgetManager gm = AppWidgetManager.getInstance(context);
    if (appWidgetIds != null) {
      gm.updateAppWidget(appWidgetIds, views);
    } else {
      gm.updateAppWidget(THIS_APPWIDGET, views);
    }
  }


  /**
   * Check against {@link AppWidgetManager} if there are any instances of this widget.
   */
  private static boolean hasInstances(Context context) {
    AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
    int[] appWidgetIds = appWidgetManager.getAppWidgetIds(THIS_APPWIDGET);
    return (appWidgetIds.length > 0);
  }


  /**
   * Called by SonosService when something happens that might need the widget
   * to update (wi-fi connected/disconnected, a Sonos system found etc.)
   */
  static void notifyChange(SonosService service) {
    if (hasInstances(service)) {
      performUpdate(service, null);
    }
  }


  /**
   * Update all active widget instances by pushing changes
   */
  static void performUpdate(SonosService service, int[] appWidgetIds) {
    final Resources res = service.getResources();
    final RemoteViews views = new RemoteViews(service.getPackageName(), R.layout.sonos_widget);

    if (!service.isWifiConnected()) {
      views.setViewVisibility(R.id.overlay_disabled, View.VISIBLE);
      views.setViewVisibility(R.id.overlay_pause, View.GONE);
      views.setViewVisibility(R.id.overlay_muted, View.GONE);
      views.setViewVisibility(R.id.control_count, View.GONE);
      views.setViewVisibility(R.id.control_bubble, View.GONE);

    } else if (service.isMuted()) {
      int secondsUntilUnmute = service.getSecondsUntilUnmute();
      StringBuilder timeUntilUnmute = new StringBuilder();
      timeUntilUnmute.append(secondsUntilUnmute / 60);
      timeUntilUnmute.append(':');
      timeUntilUnmute.append(String.format("%02d", secondsUntilUnmute % 60));

      views.setViewVisibility(R.id.overlay_disabled, View.GONE);
      views.setViewVisibility(R.id.overlay_pause, View.GONE);
      views.setViewVisibility(R.id.overlay_muted, View.VISIBLE);
      views.setViewVisibility(R.id.control_count, View.VISIBLE);
      views.setViewVisibility(R.id.control_bubble, View.VISIBLE);
      views.setTextViewText(R.id.overlay_muted, timeUntilUnmute);
      views.setTextViewText(R.id.control_count, Integer.toString(service.getNumKnownSonosSystems()));

    } else {
      views.setViewVisibility(R.id.overlay_disabled, View.GONE);
      views.setViewVisibility(R.id.overlay_pause, View.VISIBLE);
      views.setViewVisibility(R.id.overlay_muted, View.GONE);
      views.setViewVisibility(R.id.control_count, View.VISIBLE);
      views.setViewVisibility(R.id.control_bubble, View.VISIBLE);
      views.setTextViewText(R.id.control_count, Integer.toString(service.getNumKnownSonosSystems()));
    }

    // Link actions buttons to intents
    linkButtons(service, views);

    pushUpdate(service, appWidgetIds, views);
  }


  private static void linkButtons(Context context, RemoteViews views) {
    Intent intent = new Intent(SonosService.PAUSETEMPORARILY_ACTION);
    intent.setComponent(new ComponentName(context, SonosService.class));
    PendingIntent pendingIntent = PendingIntent.getService(context, 0 /* no requestCode */, intent, 0 /* no flags */);
    views.setOnClickPendingIntent(R.id.control_main, pendingIntent);
  }

}
