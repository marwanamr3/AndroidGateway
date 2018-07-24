package com.uni.stuttgart.ipvs.androidgateway.gateway.scheduling;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;

import com.uni.stuttgart.ipvs.androidgateway.bluetooth.peripheral.BluetoothLeDevice;
import com.uni.stuttgart.ipvs.androidgateway.gateway.GatewayService;
import com.uni.stuttgart.ipvs.androidgateway.gateway.IGatewayService;
import com.uni.stuttgart.ipvs.androidgateway.gateway.PowerEstimator;
import com.uni.stuttgart.ipvs.androidgateway.helper.AdRecordHelper;
import com.uni.stuttgart.ipvs.androidgateway.thread.EExecutionType;
import com.uni.stuttgart.ipvs.androidgateway.thread.ExecutionTask;
import com.uni.stuttgart.ipvs.androidgateway.thread.ThreadTrackingPriority;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;

// implementation of Scheduling using Exhaustive Polling
public class ExhaustivePolling {

    private static final int SCAN_TIME = 10000; // set scanning and reading time to 10 seoonds
    private static final int SCAN_TIME_HALF = SCAN_TIME / 2; // set scanning and reading time to 10 seoonds
    private static final int PROCESSING_TIME = 60000; // set processing time to 60 seconds

    private IGatewayService iGatewayService;

    private boolean mScanning;
    private Context context;
    private boolean mProcessing;
    private int cycleCounter = 0;
    private ExecutionTask<String> executionTask;

    public ExhaustivePolling(Context context, boolean mProcessing, IGatewayService iGatewayService) {
        this.context = context;
        this.mProcessing = mProcessing;
        this.iGatewayService = iGatewayService;
    }

    public void stop() {
        mProcessing = false;
        executionTask.terminateExecutorPools();
    }

    public void start() {
        mProcessing = true;
        int N = Runtime.getRuntime().availableProcessors();
        executionTask = new ExecutionTask<>(N, N * 2);
        executionTask.setExecutionType(EExecutionType.SINGLE_THREAD_POOL);
        executionTask.submitRunnable(new RunEPScheduling());
    }

    private class RunEPScheduling implements Runnable {
        @Override
        public void run() {
            while (mProcessing) {
                // do Exhaustive Polling Part
                cycleCounter++;
                if (cycleCounter > 1) {
                    broadcastClrScrn();
                }
                broadcastUpdate("\n");
                broadcastUpdate("Start new cycle");
                broadcastUpdate("Cycle number " + cycleCounter);
                try {
                    iGatewayService.setCycleCounter(cycleCounter);
                    boolean isDataExist = iGatewayService.checkDevice(null);
                    if (isDataExist) {
                        List<String> devices = iGatewayService.getListActiveDevices();
                        // search for known device listed in database
                        for (String device : devices) {
                            iGatewayService.addQueueScanning(device, null, 0, BluetoothLeDevice.FIND_LE_DEVICE, null, 0);
                        }
                        // do normal scanning only for half of normal scanning time
                        iGatewayService.addQueueScanning(null, null, 0, BluetoothLeDevice.SCANNING, null, 0);
                        iGatewayService.addQueueScanning(null, null, 0, BluetoothLeDevice.WAIT_THREAD, null, SCAN_TIME_HALF);
                        iGatewayService.addQueueScanning(null, null, 0, BluetoothLeDevice.STOP_SCAN, null, 0);
                        iGatewayService.execScanningQueue();
                        mScanning = iGatewayService.getScanState();
                    } else {
                        // do normal scanning
                        iGatewayService.addQueueScanning(null, null, 0, BluetoothLeDevice.SCANNING, null, 0);
                        iGatewayService.addQueueScanning(null, null, 0, BluetoothLeDevice.WAIT_THREAD, null, SCAN_TIME);
                        iGatewayService.addQueueScanning(null, null, 0, BluetoothLeDevice.STOP_SCAN, null, 0);
                        iGatewayService.execScanningQueue();
                        mScanning = iGatewayService.getScanState();
                    }

                    List<BluetoothDevice> scanResults = iGatewayService.getScanResults();

                    // do Connecting by using Semaphore
                    for (BluetoothDevice device : new ArrayList<BluetoothDevice>(scanResults)) {
                        broadcastServiceInterface("Start service interface");
                        iGatewayService.doConnect(device.getAddress());
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * Clear the screen in Gateway Tab
     */
    private void broadcastClrScrn() {
        if (mProcessing) {
            final Intent intent = new Intent(GatewayService.START_NEW_CYCLE);
            context.sendBroadcast(intent);
        }
    }

    private void broadcastUpdate(String message) {
        if (mProcessing) {
            final Intent intent = new Intent(GatewayService.MESSAGE_COMMAND);
            intent.putExtra("command", message);
            context.sendBroadcast(intent);
        }
    }

    private void broadcastServiceInterface(String message) {
        if (mProcessing) {
            final Intent intent = new Intent(GatewayService.START_SERVICE_INTERFACE);
            intent.putExtra("message", message);
            context.sendBroadcast(intent);
        }
    }
}
