package com.uni.stuttgart.ipvs.androidgateway.gateway;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;

import com.uni.stuttgart.ipvs.androidgateway.bluetooth.peripheral.BluetoothLeDevice;
import com.uni.stuttgart.ipvs.androidgateway.gateway.scheduling.ExhaustivePolling;
import com.uni.stuttgart.ipvs.androidgateway.gateway.scheduling.ExhaustivePollingWithAHP;
import com.uni.stuttgart.ipvs.androidgateway.gateway.scheduling.ExhaustivePollingWithWSM;
import com.uni.stuttgart.ipvs.androidgateway.gateway.scheduling.FairExhaustivePolling;
import com.uni.stuttgart.ipvs.androidgateway.gateway.scheduling.PriorityBasedWithAHP;
import com.uni.stuttgart.ipvs.androidgateway.gateway.scheduling.PriorityBasedWithANP;
import com.uni.stuttgart.ipvs.androidgateway.gateway.scheduling.PriorityBasedWithWPM;
import com.uni.stuttgart.ipvs.androidgateway.gateway.scheduling.PriorityBasedWithWSM;
import com.uni.stuttgart.ipvs.androidgateway.gateway.scheduling.RoundRobin;
import com.uni.stuttgart.ipvs.androidgateway.gateway.scheduling.Semaphore;
import com.uni.stuttgart.ipvs.androidgateway.helper.GattDataHelper;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.IOException;

/**
 * Created by mdand on 4/10/2018.
 */

public class GatewayController extends Service {
    private final IBinder mBinder = new LocalBinder();
    private Context context;
    private Intent mIntent;
    private IGatewayService iGatewayService;

    private boolean mBound = false;
    private boolean mProcessing = false;

    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;

    private String algorithm;
    private Semaphore sem;
    private FairExhaustivePolling fep;
    private ExhaustivePolling ep;
    private ExhaustivePollingWithAHP epAhp;
    private ExhaustivePollingWithWSM epWsm;
    private RoundRobin rr;
    private PriorityBasedWithAHP ahp;
    private PriorityBasedWithANP anp;
    private PriorityBasedWithWSM wsm;
    private PriorityBasedWithWPM wpm;

    private Runnable runnablePeriodic;
    private Thread threadPeriodic;

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mIntent = intent;
        context = this;

        bindService(new Intent(context, GatewayService.class), mConnection, Context.BIND_AUTO_CREATE);
        broadcastUpdate("Bind GatewayController to GatewayService...");

        powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WakeLockController");

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mProcessing = false;
        if(wakeLock != null && wakeLock.isHeld()) {wakeLock.release();}
        stopService(mIntent);
        stopSelf();
    }

    /**
     * Gateway Controller Binding Section
     */


    @Override
    public IBinder onBind(Intent intent) { context = this; setWakeLock(); return mBinder; }

    @Override
    public boolean onUnbind(Intent intent) {
        try { iGatewayService.addQueueScanning(null, null, 0, BluetoothLeDevice.STOP_SCAN, null, 0);iGatewayService.execScanningQueue(); } catch (RemoteException e) { e.printStackTrace(); }
        try { iGatewayService.setProcessing(false); } catch (RemoteException e) { e.printStackTrace(); }

        if(fep != null) {fep.stop();}
        if(ep != null) {ep.stop();}
        if(epAhp != null) {epAhp.stop();}
        if(epWsm != null) {epWsm.stop();}
        if(rr != null) {rr.stop();}
        if(sem != null) {sem.stop();}
        if(ahp != null) {ahp.stop();}
        if(anp != null) {anp.stop();}
        if(wsm != null) {wsm.stop();}
        if(wpm != null) {wpm.stop();}

        if(threadPeriodic != null) {threadPeriodic.interrupt();}
        if(mConnection != null) {unbindService(mConnection); }
        broadcastUpdate("Unbind GatewayController to GatewayService...");
        return false;
    }

    /**
     * Class used for the client for binding to GatewayController.
     */
    public class LocalBinder extends Binder {
        GatewayController getService() {
            // Return this instance of Service so clients can call public methods
            return GatewayController.this;
        }
    }

    /**
     * Gateway Controller Main Program Section
     */

    /**
     * Defines callbacks for service binding via AIDL for IGatewayService
     */
    protected ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            iGatewayService = IGatewayService.Stub.asInterface(service);
            mBound = true;
            mProcessing = true;
            broadcastUpdate("GatewayController & GatewayService have bound...");
            initDatabase();

            try {
                // read from .xml settings file
                Document xmlFile = GattDataHelper.parseXML(new InputSource( getAssets().open("Settings.xml") ));
                NodeList list = xmlFile.getElementsByTagName("DataAlgorithm");
                Node nodeDataAlgo = list.item(0);
                Node nodeData = nodeDataAlgo.getFirstChild().getNextSibling();
                Node nodeAlgo = nodeData.getFirstChild().getNextSibling();
                algorithm = nodeAlgo.getFirstChild().getNodeValue();

                if(algorithm.equals("sem")) {
                    doScheduleSemaphore();
                } else if(algorithm.equals("ep")) {
                    doScheduleEP();
                } else if(algorithm.equals("fep")) {
                    doScheduleFEP();
                } else if(algorithm.equals("rr")) {
                    doScheduleRR();
                } else if(algorithm.equals("epAhp")) {
                    doScheduleEPwithAHP();
                } else if(algorithm.equals("epWsm")) {
                    doScheduleEPwithWSM();
                } else if(algorithm.equals("ahp")) {
                    doSchedulePriorityAHP();
                } else if(algorithm.equals("anp")) {
                    doSchedulePriorityANP();
                } else if(algorithm.equals("wsm")) {
                    doSchedulePriorityWSM();
                } else if(algorithm.equals("wpm")) {
                    doSchedulePriorityWPM();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBound = false;
        }
    };

    /**
     * Start Scheduling Algorithm Section
     */

    /*                                                                                                                               *
     * ============================================================================================================================= *
     * ============================================================================================================================= *
     */

    // Scheduling based on waiting for callback connection (Semaphore Scheduling)
    private void doScheduleSemaphore() {
        broadcastUpdate("Start Semaphore Scheduling...");
        try {
            iGatewayService.setProcessing(mProcessing);
            iGatewayService.setHandler(null, "mGatewayHandler", "Gateway");
            sem = new Semaphore(context, mProcessing, iGatewayService);
            sem.start();
        } catch (Exception e) { e.printStackTrace(); }
    }

    /*                                                                                                                               *
     * ============================================================================================================================= *
     * ============================================================================================================================= *
     */

    // scheduling using Round Robin Method
    private void doScheduleRR() {
        broadcastUpdate("Start Round Robin Scheduling...");
        try {
            iGatewayService.setProcessing(mProcessing);
            iGatewayService.setHandler(null, "mGatewayHandler", "Gateway");
            rr = new RoundRobin(context, mProcessing, iGatewayService);
            rr.start();
        } catch (Exception e) { e.printStackTrace(); }
    }

    /*                                                                                                                               *
     * ============================================================================================================================= *
     * ============================================================================================================================= *
     */

    //scheduling using Exhaustive Polling (EP)
    private void doScheduleEP() {
        broadcastUpdate("Start Exhaustive Polling Scheduling...");
        try {
            iGatewayService.setProcessing(mProcessing);
            iGatewayService.setHandler(null, "mGatewayHandler", "Gateway");
            ep = new ExhaustivePolling(context, mProcessing, iGatewayService);
            ep.start();
        } catch (Exception e) { e.printStackTrace(); }
    }

    /*                                                                                                                               *
     * ============================================================================================================================= *
     * ============================================================================================================================= *
     */

    //scheduling using Exhaustive Polling (EP) with Analytical Hierarchy Process
    private void doScheduleEPwithAHP() {
        broadcastUpdate("Start Exhaustive Polling Scheduling with AHP...");
        try {
            iGatewayService.setProcessing(mProcessing);
            iGatewayService.setHandler(null, "mGatewayHandler", "Gateway");
            epAhp = new ExhaustivePollingWithAHP(context, mProcessing, iGatewayService);
            epAhp.start();
        } catch (Exception e) { e.printStackTrace(); }
    }

    /*                                                                                                                               *
     * ============================================================================================================================= *
     * ============================================================================================================================= *
     */

    //scheduling using Exhaustive Polling (EP) with Weighted Sum Model
    private void doScheduleEPwithWSM() {
        broadcastUpdate("Start Exhaustive Polling Scheduling with WSM...");
        try {
            iGatewayService.setProcessing(mProcessing);
            iGatewayService.setHandler(null, "mGatewayHandler", "Gateway");
            epWsm = new ExhaustivePollingWithWSM(context, mProcessing, iGatewayService);
            epWsm.start();
        } catch (Exception e) { e.printStackTrace(); }
    }

    /*                                                                                                                               *
     * ============================================================================================================================= *
     * ============================================================================================================================= *
     */

    // scheduling using Fair Exhaustive Polling (FEP)
    private void doScheduleFEP() {
        broadcastUpdate("Start Fair Exhaustive Polling Scheduling...");
        try {
            iGatewayService.setProcessing(mProcessing);
            iGatewayService.setHandler(null, "mGatewayHandler", "Gateway");
            fep =  new FairExhaustivePolling(context, mProcessing, iGatewayService);
            fep.start();
        } catch (Exception e) { e.printStackTrace(); }
    }

    /*                                                                                                                               *
     * ============================================================================================================================= *
     * ============================================================================================================================= *
     */

    // scheduling based on Priority with AHP decision making algorithm
    private void doSchedulePriorityAHP() {
        broadcastUpdate("Start Priority Scheduling with AHP...");
        try {
            iGatewayService.setProcessing(mProcessing);
            iGatewayService.setHandler(null, "mGatewayHandler", "Gateway");
            ahp = new PriorityBasedWithAHP(context, mProcessing, iGatewayService);
            ahp.start();
        } catch (Exception e) { e.printStackTrace(); }
    }

    
    /*                                                                                                                               *
     * ============================================================================================================================= *
     * ============================================================================================================================= *
     */

    // scheduling based on Priority with ANP decision making algorithm
    private void doSchedulePriorityANP() {
        broadcastUpdate("Start Priority Scheduling with ANP...");
        try {
            iGatewayService.setProcessing(mProcessing);
            iGatewayService.setHandler(null, "mGatewayHandler", "Gateway");
            anp = new PriorityBasedWithANP(context, mProcessing, iGatewayService);
            anp.start();
        } catch (Exception e) { e.printStackTrace(); }
    }

    /*                                                                                                                               *
     * ============================================================================================================================= *
     * ============================================================================================================================= *
     */

    // scheduling based on Priority with WSM decision making algorithm
    private void doSchedulePriorityWSM() {
        broadcastUpdate("Start Priority Scheduling with WSM...");
        try {
            iGatewayService.setProcessing(mProcessing);
            iGatewayService.setHandler(null, "mGatewayHandler", "Gateway");
            wsm = new PriorityBasedWithWSM(context, mProcessing, iGatewayService);
            wsm.start();
        } catch (Exception e) { e.printStackTrace(); }
    }

    /*                                                                                                                               *
     * ============================================================================================================================= *
     * ============================================================================================================================= *
     */

    // scheduling based on Priority with WPM decision making algorithm
    private void doSchedulePriorityWPM() {
        broadcastUpdate("Start Priority Scheduling with WPM...");
        try {
            iGatewayService.setProcessing(mProcessing);
            iGatewayService.setHandler(null, "mGatewayHandler", "Gateway");
            wpm = new PriorityBasedWithWPM(context, mProcessing, iGatewayService);
            wpm.start();
        } catch (Exception e) { e.printStackTrace(); }
    }



    /**
     * End of Algorithm Section
     */

    /*                                                                                                                               *
     * ============================================================================================================================= *
     * ============================================================================================================================= *
     */

    /**
     * Start Method Routine
     */

    private void initDatabase() {
        try {
            broadcastUpdate("Initialize database...");
            broadcastUpdate("\n");
            iGatewayService.initializeDatabase();
            iGatewayService.insertDatabaseManufacturer("0x0157", "Anhui Huami Information Technology");
            iGatewayService.insertDatabaseManufacturer("0x0401", "Vemiter Lamp Service");
            iGatewayService.insertDatabaseManufacturer("0x0001", "Nokia Mobile Phones");
            iGatewayService.insertDatabaseManufacturer("0xffff", "Testing Devices");
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }
    private void setWakeLock() {
        if((wakeLock != null) && (!wakeLock.isHeld())) { wakeLock.acquire(); }
    }

    private void broadcastUpdate(String message) {
        if (mProcessing) {
            final Intent intent = new Intent(GatewayService.MESSAGE_COMMAND);
            intent.putExtra("command", message);
            sendBroadcast(intent);
        }
    }

}
