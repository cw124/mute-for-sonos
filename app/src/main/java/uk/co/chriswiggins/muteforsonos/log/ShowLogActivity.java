package uk.co.chriswiggins.muteforsonos.log;

import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

import uk.co.chriswiggins.muteforsonos.R;


/**
 * Activity to show the app's log file.
 */
public class ShowLogActivity extends ActionBarActivity {

  private static final String TAG = "ShowLogActivity";


  @Override
  protected void onCreate(Bundle bundle) {
    super.onCreate(bundle);

    String logFile = getIntent().getStringExtra(LogManager.LOG_FILE);

    setContentView(R.layout.activity_show_log);

    try {
      BufferedReader bufferedReader = new BufferedReader(new FileReader(logFile));

      StringBuilder log = new StringBuilder();
      String line;

      while ((line = bufferedReader.readLine()) != null) {
        // Example log line:
        // 01-29 22:40:42.025 I/SonosService(20037): onStartCommand

        // Split after ) so lines fit on small screens better.
        // Some lines are different ("--------- beginning of /dev/log/main"),
        // so leave those alone.

        int i = line.indexOf(')');

        if (i == -1) {
          log.append(line);

        } else {
          log.append(line.substring(0, i + 2));
          log.append('\n');
          log.append(line.substring(i + 3));
        }

        log.append('\n');
        log.append('\n');
      }

      TextView logText = (TextView) findViewById(R.id.logText);
      logText.setText(log.toString());

    } catch (IOException e) {
      Log.w(TAG, "Could not read log file", e);
    }
  }

}
