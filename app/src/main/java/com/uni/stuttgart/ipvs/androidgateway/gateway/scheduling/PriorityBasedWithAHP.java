package com.uni.stuttgart.ipvs.androidgateway.gateway.scheduling;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.RemoteException;

import com.uni.stuttgart.ipvs.androidgateway.bluetooth.peripheral.BluetoothLeDevice;
import com.uni.stuttgart.ipvs.androidgateway.gateway.GatewayService;
import com.uni.stuttgart.ipvs.androidgateway.gateway.IGatewayService;
import com.uni.stuttgart.ipvs.androidgateway.gateway.PowerEstimator;
import com.uni.stuttgart.ipvs.androidgateway.gateway.mcdm.AHP;
import com.uni.stuttgart.ipvs.androidgateway.helper.DataSorterHelper;
import com.uni.stuttgart.ipvs.androidgateway.thread.ProcessPriority;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class PriorityBasedWithAHP implements Runnable {

    private static final int SCAN_TIME = 10000; // set scanning and reading time to 10 seoonds
    private static final int PROCESSING_TIME = 60000; // set processing time to 60 seconds
    private static final int NUMBER_OF_MAX_CONNECT_DEVICES = 10; // set max 10 devices connect before listening to disconnection time

    private ScheduledThreadPoolExecutor schedulerPower;
    private ScheduledThreadPoolExecutor scheduler;
    private ScheduledThreadPoolExecutor scheduler2;
    private ScheduledFuture<?> future;
    private ScheduledFuture<?> future2;

    private Thread sleepThread;
    private IGatewayService iGatewayService;
    private PowerEstimator powerEstimator;

    private boolean mScanning;
    private Context context;
    private boolean mProcessing;
    private boolean isBroadcastRegistered;

    private int cycleCounter = 0;
    private int maxConnectTime = 0;
    private long powerUsage = 0;
    private int connectCounter = 0;

    private ProcessPriority processConnecting;

    public PriorityBasedWithAHP(Context context, boolean mProcessing, IGatewayService iGatewayService) {
        this.context = context;
        this.mProcessing = mProcessing;
        this.iGatewayService = iGatewayService;
    }

    @Override
    public void run() {
        try {
            isBroadcastRegistered = false;
            powerEstimator = new PowerEstimator(context);
            scheduler = new ScheduledThreadPoolExecutor(5);
            future = scheduler.scheduleAtFixedRate(new FPStartScanning(), 0, PROCESSING_TIME + 1, MILLISECONDS);
            scheduler2 = new ScheduledThreadPoolExecutor(5);
            future2 = scheduler2.scheduleAtFixedRate(new FPDeviceDbRefresh(), 5 * PROCESSING_TIME, 5 * PROCESSING_TIME, MILLISECONDS); // refresh db state after 5 minutes
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private class FPStartScanning implements Runnable {

        @Override
        public void run() {
            try {
                cycleCounter++;
                if(cycleCounter > 1) {broadcastClrScrn();}
                broadcastUpdate("Start new cycle");

                broadcastUpdate("Cycle number " + cycleCounter);
                mProcessing = true;
                boolean isDataExist = iGatewayService.checkDevice(null);
                if (isDataExist) {

                    List<String> devices = iGatewayService.getListActiveDevices();
                    for (String device : devices) {
                        iGatewayService.addQueueScanning(device, null, 0, BluetoothLeDevice.FIND_LE_DEVICE, null);
                    }
                    iGatewayService.execScanningQueue();
                    mScanning = iGatewayService.getScanState();

                    // do normal scanning only for half of normal scanning time
                    iGatewayService.addQueueScanning(null, null, 0, BluetoothLeDevice.SCANNING, null);
                    iGatewayService.execScanningQueue();
                    mScanning = iGatewayService.getScanState();
                    waitThread(SCAN_TIME / 2);

                    if (!mProcessing) {
                        future.cancel(false);
                        return;
                    }

                    stop();
                    waitThread(100);

                    if (!mProcessing) {
                        future.cancel(false);
                        return;
                    }
                    connectFP();
                } else {
                    // do normal scanning
                    iGatewayService.addQueueScanning(null, null, 0, BluetoothLeDevice.SCANNING, null);
                    iGatewayService.execScanningQueue();
                    mScanning = iGatewayService.getScanState();
                    waitThread(SCAN_TIME);

                    if (!mProcessing) {
                        future.cancel(false);
                        stop();
                        return;
                    }

                    stop();
                    waitThread(100);

                    if (!mProcessing) {
                        future.cancel(false);
                        return;
                    }
                    connectRR();
                }


            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void stop() {
            if (mScanning) {
                try {
                    iGatewayService.addQueueScanning(null, null, 0, BluetoothLeDevice.STOP_SCANNING, null);
                    iGatewayService.execScanningQueue();
                    mScanning = iGatewayService.getScanState();
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }

            if (!mProcessing) {
                future.cancel(false);
                return;
            }
        }

        // 1st iteration connect using Round Robin Method
        private void connectRR() {
            try {
                List<BluetoothDevice> scanResults = iGatewayService.getScanResults();
                // calculate timer for connection (to obtain Round Robin Scheduling)
                if (scanResults.size() != 0) {
                    int remainingTime = PROCESSING_TIME - SCAN_TIME;
                    maxConnectTime = remainingTime / scanResults.size();
                    broadcastUpdate("Maximum connection time for all devices is " + maxConnectTime / 1000 + " s");
                } else {
                    return;
                }

                // do connecting by Round Robin
                for (final BluetoothDevice device : scanResults) {
                    iGatewayService.updateDatabaseDeviceState(device, "inactive");
                    processUserChoiceAlert(device.getAddress(), device.getName());

                    powerUsage = 0;
                    powerEstimator.start();

                    processConnecting = new ProcessPriority(10);
                    processConnecting.newThread(doConnecting(device.getAddress())).start();

                    schedulerPower = new ScheduledThreadPoolExecutor(5);
                    schedulerPower.scheduleAtFixedRate(doMeasurePower(), 0, 100, MILLISECONDS);

                    // set timer to xx seconds
                    waitThread(maxConnectTime);
                    if (!mProcessing) {
                        return;
                    }

                    broadcastUpdate("Wait time finished, disconnected...");
                    iGatewayService.doDisconnected(iGatewayService.getCurrentGatt(), "GatewayController");
                    waitThread(100);

                    processConnecting.interruptThread();

                    schedulerPower.shutdownNow();
                    powerEstimator.stop();
                    iGatewayService.updateDatabaseDevicePowerUsage(device.getAddress(), powerUsage);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 2nd and so on iterations, connect using Fixed Priority Scheduling by device ranking
        private void connectFP() {
            try {
                /*registerBroadcast(); // start listening to disconnected Gatt and or finished read data*/
                connectCounter = 0;
                Map<BluetoothDevice, Double> mapRankedDevices = doRankDeviceAHP(iGatewayService.getScanResults());

                // calculate timer for connection (to obtain Round Robin Scheduling)
                int remainingTime = PROCESSING_TIME - SCAN_TIME;

                if(mapRankedDevices.size() > 0) {
                    DataSorterHelper<BluetoothDevice> sortData = new DataSorterHelper<>();
                    mapRankedDevices = sortData.sortMapByComparatorDouble(mapRankedDevices, false);
                    broadcastUpdate("Sorting devices by their priorities...");

                    broadcastUpdate("\n");
                    maxConnectTime = remainingTime / mapRankedDevices.size();
                    broadcastUpdate("Connecting to " + mapRankedDevices.size() + " device(s)");
                    broadcastUpdate("Maximum connection time for all devices is " + maxConnectTime / 1000 + " s");
                    broadcastUpdate("\n");

                    if(mapRankedDevices.size() > NUMBER_OF_MAX_CONNECT_DEVICES) {
                        // if more than 10 devices, try to listen to disconnect time before finish waiting
                        // (ignoring maxConnectTime)
                        isBroadcastRegistered = registerBroadcastListener();
                        connect(mapRankedDevices, remainingTime);
                        if(isBroadcastRegistered) {unregisterBroadcastListener();}
                    } else {
                        // if less than 10 devices, waiting time is based on maxConnectTime
                        connect(mapRankedDevices, remainingTime);
                    }

                } else {
                    broadcastUpdate("No nearby device available");
                    return;
                }


            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // implementation of connect after ranking the devices
        private void connect(Map<BluetoothDevice, Double> mapRankedDevices, int remainingTime) {
            try {
                for (Map.Entry entry : mapRankedDevices.entrySet()) {
                    BluetoothDevice device = (BluetoothDevice) entry.getKey();
                    iGatewayService.updateDatabaseDeviceState(device, "inactive");
                    processUserChoiceAlert(device.getAddress(), device.getName());

                    powerUsage = 0;
                    powerEstimator.start();

                    processConnecting = new ProcessPriority(10);
                    processConnecting.newThread(doConnecting(device.getAddress())).start();

                    schedulerPower = new ScheduledThreadPoolExecutor(5);
                    schedulerPower.scheduleAtFixedRate(doMeasurePower(), 0, 100, MILLISECONDS);

                    // set timer to xx seconds
                    waitThread(maxConnectTime);
                    if (!mProcessing) { return; }

                    broadcastUpdate("Wait time finished, disconnected...");
                    iGatewayService.doDisconnected(iGatewayService.getCurrentGatt(), "GatewayController");
                    waitThread(10);
                    processConnecting.interruptThread();

                    schedulerPower.shutdownNow();
                    powerEstimator.stop();
                    iGatewayService.updateDatabaseDevicePowerUsage(device.getAddress(), powerUsage);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

        }

        // implementation of Ranking Devices based on AHP
        private Map<BluetoothDevice, Double> doRankDeviceAHP(List<BluetoothDevice> devices) {
            try {
                broadcastUpdate("\n");
                broadcastUpdate("Start ranking device with AHP algorithm...");

                Map<BluetoothDevice, Double> rankedDevices = new ConcurrentHashMap<>();
                Map<BluetoothDevice, Object[]> mapParameters = new ConcurrentHashMap<>();
                for (BluetoothDevice device : devices) {
                    int rssi = iGatewayService.getDeviceRSSI(device.getAddress());
                    String deviceState = iGatewayService.getDeviceState(device.getAddress());
                    String userChoice = iGatewayService.getDeviceUsrChoice(device.getAddress());
                    long powerUsage = iGatewayService.getDevicePowerUsage(device.getAddress());

                    long batteryRemaining = powerEstimator.getBatteryRemainingPercent();
                    double[] powerConstraints = iGatewayService.getPowerUsageConstraints((double) batteryRemaining);

                    Object[] parameters = new Object[5];
                    parameters[0] = rssi;
                    parameters[1] = deviceState;
                    parameters[2] = userChoice;
                    parameters[3] = powerUsage;
                    parameters[4] = powerConstraints;
                    mapParameters.put(device, parameters);
                }

                AHP ahp = new AHP(mapParameters);
                AsyncTask<Void, Void, Map<BluetoothDevice, Double>> rankingTask = ahp.execute();
                rankedDevices = rankingTask.get();
                broadcastUpdate("Finish ranking device...");
                return rankedDevices;
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }
    }

    private class FPDeviceDbRefresh implements Runnable {
        @Override
        public void run() {
            if (!mProcessing) {
                future2.cancel(true);
                return;
            }
            broadcastUpdate("Update all device states...");
            if (mProcessing) {
                try {
                    iGatewayService.updateAllDeviceStates(null);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            } else {
                future2.cancel(false);
            }
        }
    }

    /**
     * =================================================================================================================================
     * Broadcast Listener
     * =================================================================================================================================
     */

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(GatewayService.DISCONNECT_COMMAND)) {
                String message = intent.getStringExtra("command");
                if(sleepThread != null && !sleepThread.isInterrupted()) {sleepThread.interrupt();}
            } else if (action.equals(GatewayService.FINISH_READ)) {
                String message = intent.getStringExtra("command");
                if(sleepThread != null && !sleepThread.isInterrupted()) {sleepThread.interrupt();}
            }
        }

    };


    /**
     * =================================================================================================================================
     * Method Routines Section
     * =================================================================================================================================
     */

    private boolean registerBroadcastListener() {
        context.registerReceiver(mReceiver, new IntentFilter(GatewayService.DISCONNECT_COMMAND));
        context.registerReceiver(mReceiver, new IntentFilter(GatewayService.FINISH_READ));
        return true;
    }

    private void unregisterBroadcastListener() {
        context.unregisterReceiver(mReceiver);
    }

    private Runnable doMeasurePower() {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    long currentNow = powerEstimator.getCurrentNow();
                    if (currentNow < 0) { currentNow = currentNow * -1; }
                    powerUsage = powerUsage + (currentNow * new Long(powerEstimator.getVoltageNow()));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };
    }

    private Runnable doConnecting(final String macAddress) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    iGatewayService.doConnect(macAddress);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        };
    }

    private void waitThread(long time) {
        try {
            sleepThread = Thread.currentThread();
            Thread.sleep(time);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void broadcastUpdate(String message) {
        if (mProcessing) {
            final Intent intent = new Intent(GatewayService.MESSAGE_COMMAND);
            intent.putExtra("command", message);
            context.sendBroadcast(intent);
        }
    }

    private void broadcastClrScrn() {
        if (mProcessing) {
            final Intent intent = new Intent(GatewayService.START_NEW_CYCLE);
            context.sendBroadcast(intent);
        }
    }

    private void processUserChoiceAlert(String macAddress, String deviceName) {
        try {
            String userChoice = iGatewayService.getDeviceUsrChoice(macAddress);
            if (deviceName == null) { deviceName = "Unknown"; }
            if (userChoice == null || userChoice == "")
                broadcastAlertDialog("Start Service Interface of Device " + macAddress + "-" + deviceName, macAddress);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void broadcastAlertDialog(String message, String macAddress) {
        if (mProcessing) {
            final Intent intent = new Intent(GatewayService.USER_CHOICE_SERVICE);
            intent.putExtra("message", message);
            intent.putExtra("macAddress", macAddress);
            context.sendBroadcast(intent);
        }
    }

}
