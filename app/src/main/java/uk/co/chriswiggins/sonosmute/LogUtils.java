package uk.co.chriswiggins.sonosmute;

import java.util.logging.Level;
import java.util.logging.Logger;


public class LogUtils {

  public static void setupLogging() {
    // Configure Cling and other stuff to log reasonable amounts of stuff (rather than loads).
    Logger.getLogger("org.teleal.cling").setLevel(Level.INFO);
    Logger.getLogger("org.teleal.cling.protocol").setLevel(Level.SEVERE);
    Logger.getLogger("sun").setLevel(Level.INFO);
    Logger.getLogger("com").setLevel(Level.INFO);
  }
}
