package com.uni.stuttgart.ipvs.androidgateway.gateway;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;

import com.uni.stuttgart.ipvs.androidgateway.bluetooth.peripheral.BluetoothLeDevice;
import com.uni.stuttgart.ipvs.androidgateway.gateway.scheduling.ExhaustivePolling;
import com.uni.stuttgart.ipvs.androidgateway.gateway.scheduling.FairExhaustivePolling;
import com.uni.stuttgart.ipvs.androidgateway.gateway.scheduling.PriorityBasedWithAHP;
import com.uni.stuttgart.ipvs.androidgateway.gateway.scheduling.RoundRobin;
import com.uni.stuttgart.ipvs.androidgateway.gateway.scheduling.Semaphore;
import com.uni.stuttgart.ipvs.androidgateway.thread.ProcessPriority;

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

    private ProcessPriority process;
    private Runnable runnablePeriodic;
    private Thread threadPeriodic;

    private AsyncTask<Void, Void, Void> schedulingTask;

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
        if(schedulingTask != null) {schedulingTask.cancel(true);}
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
        try { iGatewayService.addQueueScanning(null, null, 0, BluetoothLeDevice.STOP_SCAN, null);iGatewayService.execScanningQueue(); } catch (RemoteException e) { e.printStackTrace(); }
        try { iGatewayService.setProcessing(false); } catch (RemoteException e) { e.printStackTrace(); }
        if(process != null) {process.interruptThread();}
        broadcastUpdate("Unbind GatewayController to PowerEstimator...");
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

            //doScheduleSemaphore();
            //doScheduleRR();
            //doScheduleEP();
            //doScheduleFEP();
            //doSchedulePriorityAHP();
            doSchedulePriorityANP();
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
            process = new ProcessPriority(10);
            runnablePeriodic = new Semaphore(context, mProcessing, iGatewayService);
            threadPeriodic = process.newThread(runnablePeriodic);
            threadPeriodic.setName("Semaphore");
            threadPeriodic.start();
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
            process = new ProcessPriority(10);
            runnablePeriodic = new RoundRobin(context, mProcessing, iGatewayService);
            threadPeriodic = process.newThread(runnablePeriodic);
            threadPeriodic.setName("Round Robin");
            threadPeriodic.start();
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
            process = new ProcessPriority(10);
            runnablePeriodic = new ExhaustivePolling(context, mProcessing, iGatewayService);
            threadPeriodic = process.newThread(runnablePeriodic);
            threadPeriodic.setName("Exhaustive Polling");
            threadPeriodic.start();
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
            process = new ProcessPriority(10);
            runnablePeriodic = new FairExhaustivePolling(context, mProcessing, iGatewayService);
            threadPeriodic = process.newThread(runnablePeriodic);
            threadPeriodic.setName("Fair Exhaustive Polling");
            threadPeriodic.start();
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
            process = new ProcessPriority(10);
            runnablePeriodic = new PriorityBasedWithAHP(context, mProcessing, iGatewayService);
            threadPeriodic = process.newThread(runnablePeriodic);
            threadPeriodic.start();
            threadPeriodic.setName("Fixed Priority");
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
            process = new ProcessPriority(10);
            runnablePeriodic = new PriorityBasedWithAHP(context, mProcessing, iGatewayService);
            threadPeriodic = process.newThread(runnablePeriodic);
            threadPeriodic.start();
            threadPeriodic.setName("Fixed Priority");
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
            iGatewayService.insertDatabasePowerUsage("Case1", 60, 100, 1 * Math.pow(10, 14), 1 * Math.pow(10, 15), 1 * Math.pow(10, 15));
            iGatewayService.insertDatabasePowerUsage("Case2", 20, 60, 1 * Math.pow(10, 13), 1 * Math.pow(10, 14), 1 * Math.pow(10, 14));
            iGatewayService.insertDatabasePowerUsage("Case3", 0, 20, 1 * Math.pow(10, 12), 1 * Math.pow(10, 13), 1 * Math.pow(10, 13));
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void updateDeviceUserChoice(String macAddress, String userChoice) {
        if(mBound) {
            try {
                iGatewayService.updateDatabaseDeviceUsrChoice(macAddress, userChoice);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        if(userChoice.equals("Yes")) {broadcastServiceInterface("Start Service Interface");;}
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

    private void broadcastServiceInterface(String message) {
        if (mProcessing) {
            final Intent intent = new Intent(GatewayService.START_SERVICE_INTERFACE);
            intent.putExtra("message", message);
            sendBroadcast(intent);
        }
    }

}
