package uk.co.chriswiggins.sonosmute;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.view.View;
import android.widget.RemoteViews;

import java.util.logging.Logger;


public class SonosWidgetProvider extends AppWidgetProvider {

  private static Logger logger = Logger.getLogger(SonosWidgetProvider.class.getCanonicalName());

  public static final String CMDAPPWIDGETUPDATE = "appwidgetupdate";


  static final ComponentName THIS_APPWIDGET = new ComponentName("uk.co.chriswiggins.sonosmute",
                  "uk.co.chriswiggins.sonosmute.SonosWidgetProvider");


  private static SonosWidgetProvider instance;


  static synchronized SonosWidgetProvider getInstance() {
    if (instance == null) {
      instance = new SonosWidgetProvider();
    }
    return instance;
  }


  @Override
  public void onEnabled(Context context) {
    logger.info("onEnabled");

    logger.info("Starting SonosService");
    Intent intent = new Intent(context.getApplicationContext(), SonosService.class);
    context.startService(intent);
  }


  @Override
  public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
    defaultAppWidget(context, appWidgetIds);

    // Send broadcast intent to any running SonosService so it can
    // wrap around with an immediate update.
    Intent updateIntent = new Intent(SonosService.SERVICECMD);
    updateIntent.putExtra(SonosService.CMDNAME, SonosWidgetProvider.CMDAPPWIDGETUPDATE);
    updateIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds);
    updateIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
    context.sendBroadcast(updateIntent);
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


  private void pushUpdate(Context context, int[] appWidgetIds, RemoteViews views) {
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
  private boolean hasInstances(Context context) {
    AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
    int[] appWidgetIds = appWidgetManager.getAppWidgetIds(THIS_APPWIDGET);
    return (appWidgetIds.length > 0);
  }


  /**
   * Called by SonosService when something happens that might need the widget
   * to update (wi-fi connected/disconnected, a Sonos system found etc.)
   */
  void notifyChange(SonosService service, SonosService.Change change) {
    if (hasInstances(service)) {
      performUpdate(service, null);
    }
  }


  /**
   * Update all active widget instances by pushing changes
   */
  void performUpdate(SonosService service, int[] appWidgetIds) {
    final Resources res = service.getResources();
    final RemoteViews views = new RemoteViews(service.getPackageName(), R.layout.sonos_widget);

    if (!service.isWifiConnected()) {
      views.setViewVisibility(R.id.control_disabled, View.VISIBLE);
      views.setViewVisibility(R.id.control_pause, View.GONE);
      views.setViewVisibility(R.id.control_muted, View.GONE);
      views.setViewVisibility(R.id.control_count, View.GONE);

    } else if (service.isMuted()) {
      views.setViewVisibility(R.id.control_disabled, View.GONE);
      views.setViewVisibility(R.id.control_pause, View.GONE);
      views.setViewVisibility(R.id.control_muted, View.VISIBLE);
      views.setViewVisibility(R.id.control_count, View.VISIBLE);
      views.setTextViewText(R.id.control_muted, Integer.toString(service.getSecondsUntilUnmute()));
      views.setTextViewText(R.id.control_count, Integer.toString(service.getNumKnownSonosSystems()));

    } else {
      views.setViewVisibility(R.id.control_disabled, View.GONE);
      views.setViewVisibility(R.id.control_pause, View.VISIBLE);
      views.setViewVisibility(R.id.control_muted, View.GONE);
      views.setViewVisibility(R.id.control_count, View.VISIBLE);
      views.setTextViewText(R.id.control_count, Integer.toString(service.getNumKnownSonosSystems()));
    }

    // Link actions buttons to intents
    linkButtons(service, views);

    pushUpdate(service, appWidgetIds, views);
  }


  private void linkButtons(Context context, RemoteViews views) {
    Intent intent = new Intent(SonosService.PAUSETEMPORARILY_ACTION);
    intent.setComponent(new ComponentName(context, SonosService.class));
    PendingIntent pendingIntent = PendingIntent.getService(context, 0 /* no requestCode */, intent, 0 /* no flags */);
    views.setOnClickPendingIntent(R.id.control_pause, pendingIntent);
    views.setOnClickPendingIntent(R.id.control_muted, pendingIntent);
  }

}
