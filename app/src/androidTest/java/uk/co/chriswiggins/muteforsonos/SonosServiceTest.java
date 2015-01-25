package uk.co.chriswiggins.muteforsonos;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.test.ServiceTestCase;


public class SonosServiceTest extends ServiceTestCase<SonosService> {

  private Intent startServiceIntent;

  public SonosServiceTest() {
    super(SonosService.class);
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    startServiceIntent = new Intent(getContext(), SonosService.class);
  }



  public void testStartable() {
    startService(startServiceIntent);
    assertNotNull(getService());
  }


  public void testUpnpServiceStartedWhenSonosServiceStarted() {
    SonosContextWrapper context = new SonosContextWrapper(getContext());
    setContext(context);
    startService(startServiceIntent);
    assertEquals("org.teleal.cling.android.AndroidUpnpServiceImpl", context.service.getComponent().getClassName());
  }


  public void testWifiReceiver() {
    // TODO: finish this test, although is it actually useful?

//    startService(startServiceIntent);
//    SonosService.WiFiBroadcastReceiver r = ((SonosService) getService()).wiFiBroadcastReceiver;
//
//    Intent i = new Intent("MY_ACTION");
//    // TODO put extras
//    r.onReceive(getContext(), i);
//    // TODO query application state to verify results
  }


  private class SonosContextWrapper extends ContextWrapper {
    Intent service;

    public SonosContextWrapper(Context context) {
      super(context);
    }

    @Override
    public Context getApplicationContext() {
      return new ContextWrapper(super.getApplicationContext()) {
        @Override
        public boolean bindService(Intent service, ServiceConnection conn, int flags) {
          SonosContextWrapper.this.service = service;
          return super.bindService(service, conn, flags);
        }
      };
    }
  }
}
