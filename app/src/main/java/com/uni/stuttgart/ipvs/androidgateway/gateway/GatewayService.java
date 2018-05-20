package com.uni.stuttgart.ipvs.androidgateway.gateway;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import com.uni.stuttgart.ipvs.androidgateway.bluetooth.peripheral.BluetoothLeDevice;
import com.uni.stuttgart.ipvs.androidgateway.bluetooth.peripheral.BluetoothLeGatt;
import com.uni.stuttgart.ipvs.androidgateway.bluetooth.callback.BluetoothLeGattCallback;
import com.uni.stuttgart.ipvs.androidgateway.bluetooth.BluetoothLeScanProcess;
import com.uni.stuttgart.ipvs.androidgateway.database.BleDeviceDatabase;
import com.uni.stuttgart.ipvs.androidgateway.database.CharacteristicsDatabase;
import com.uni.stuttgart.ipvs.androidgateway.database.ServicesDatabase;
import com.uni.stuttgart.ipvs.androidgateway.helper.GattDataHelper;
import com.uni.stuttgart.ipvs.androidgateway.helper.GattDataJson;
import com.uni.stuttgart.ipvs.androidgateway.helper.GattDataLookUp;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Created by mdand on 3/17/2018.
 */

public class GatewayService extends Service {
    private static final String TAG = "GatewayService";

    public static final String MESSAGE_COMMAND =
            "com.uni-stuttgart.ipvs.androidgateway.gateway.MESSAGE_COMMAND";
    public static final String TERMINATE_COMMAND =
            "com.uni-stuttgart.ipvs.androidgateway.gateway.TERMINATE_COMMAND";
    public static final String START_COMMAND =
            "com.uni-stuttgart.ipvs.androidgateway.gateway.START_COMMAND";
    public static final String START_SERVICE_INTERFACE =
            "com.uni-stuttgart.ipvs.androidgateway.gateway.START_SERVICE_INTERFACE";
    public static final String USER_CHOICE_SERVICE =
            "com.uni-stuttgart.ipvs.androidgateway.gateway.USER_CHOICE_SERVICE";
    public static final String DISCONNECT_COMMAND =
            "com.uni-stuttgart.ipvs.androidgateway.gateway.DISCONNECT_COMMAND";
    public static final String FINISH_READ =
            "com.uni-stuttgart.ipvs.androidgateway.gateway.FINISH_READ";

    private Intent mIntent;
    private Context context;

    private ConcurrentLinkedQueue queueScanning;
    private ConcurrentLinkedQueue queueConnecting;
    private ConcurrentLinkedQueue queueCharacteristic;

    private BluetoothLeDevice bleDevice;
    private BluetoothLeGatt bleGatt;
    private GatewayCallback gatewayCallback;
    private Object lock;

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanProcess mBluetoothLeScanProcess;
    private BluetoothLeGattCallback mBluetoothGattCallback;
    private List<BluetoothDevice> scanResults;
    private List<BluetoothGatt> listBluetoothGatt;
    private Map<BluetoothGatt, BluetoothLeGattCallback> mapGattCallback;
    private BluetoothGatt mBluetoothGatt;

    private HandlerThread mThread = new HandlerThread("mThreadCallback");
    private Handler mHandlerMessage;
    private boolean mProcessing;
    private boolean mScanning;
    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;

    private BleDeviceDatabase bleDeviceDatabase = new BleDeviceDatabase(this);
    private ServicesDatabase bleServicesDatabase = new ServicesDatabase(this);
    private CharacteristicsDatabase bleCharacteristicDatabase = new CharacteristicsDatabase(this);

    private String status;

    @Override
    public void onCreate() {
        super.onCreate();

        mThread.start();
        gatewayCallback = new GatewayCallback(context, mProcessing, mBinder);
        mHandlerMessage = new Handler(mThread.getLooper(), gatewayCallback);
        gatewayCallback.setmHandlerMessage(mHandlerMessage);

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothLeScanProcess = new BluetoothLeScanProcess(this, mBluetoothAdapter);

        queueScanning = new ConcurrentLinkedQueue();
        queueConnecting = new ConcurrentLinkedQueue();
        queueCharacteristic = new ConcurrentLinkedQueue();
        bleDevice = new BluetoothLeDevice();
        bleGatt = new BluetoothLeGatt();
        lock = new Object();

        listBluetoothGatt = new ArrayList<>();
        mapGattCallback = new HashMap<>();
        scanResults = new ArrayList<>();

        status = "Created";
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mIntent = intent;
        context = this;
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        disconnect();
    }

    @Override
    public IBinder onBind(Intent intent) {
        mIntent = intent;
        context = this;
        setWakeLock();
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        disconnect();
        return false;
    }

    /**
     * method used for binding GatewayService to many processes outside this class
     */

    private final IGatewayService.Stub mBinder = new IGatewayService.Stub() {
        @Override
        public int getPid() throws RemoteException {
            return Process.myPid();
        }

        @Override
        public void basicTypes(int anInt, long aLong, boolean aBoolean, float aFloat, double aDouble, String aString) throws RemoteException {
        }

        @Override
        public String getCurrentStatus() throws RemoteException {
            return status;
        }

        @Override
        public void setMessageHandler(PMessageHandler messageHandler) throws RemoteException {
            Handler handler = messageHandler.getHandlerMessage();
            if (handler != null) {
                mHandlerMessage = handler;
            }
        }

        @Override
        public PMessageHandler getMessageHandler() throws RemoteException {
            PMessageHandler parcelMessageHandler = new PMessageHandler();
            parcelMessageHandler.setHandlerMessage(mHandlerMessage);
            return parcelMessageHandler;
        }

        @Override
        public PHandlerThread getHandlerThread() throws RemoteException {
            PHandlerThread handlerThread = new PHandlerThread();
            handlerThread.setHandlerThread(mThread);
            return handlerThread;
        }

        @Override
        public void setProcessing(boolean processing) throws RemoteException {
            mProcessing = processing;
        }

        @Override
        public boolean getScanState() throws RemoteException {
            return mScanning;
        }

        @Override
        public void setScanResult(List<BluetoothDevice> scanResult) throws RemoteException {
            scanResults = scanResult;
        }

        @Override
        public List<BluetoothDevice> getScanResults() throws RemoteException {
            return scanResults;
        }

        @Override
        public void setCurrentGatt(PBluetoothGatt gatt) throws RemoteException {
            PBluetoothGatt parcelBluetoothGatt = new PBluetoothGatt();
            mBluetoothGatt = parcelBluetoothGatt.getGatt();
        }

        @Override
        public PBluetoothGatt getCurrentGatt() throws RemoteException {
            PBluetoothGatt parcelBluetoothGatt = new PBluetoothGatt();
            parcelBluetoothGatt.setGatt(mBluetoothGatt);
            return parcelBluetoothGatt;
        }

        @Override
        public void setListGatt(List<PBluetoothGatt> listGatt) throws RemoteException {
            if (listGatt.size() > 0) {
                listBluetoothGatt = new ArrayList<>();
                for (PBluetoothGatt parcelBluetoothGatt : listGatt) {
                    listBluetoothGatt.add(parcelBluetoothGatt.getGatt());
                }
            }
        }

        @Override
        public void addQueueScanning(String macAddress, String name, int rssi, int typeCommand, ParcelUuid serviceUUID) throws RemoteException {
            UUID uuidService = null;
            if (serviceUUID != null) {
                uuidService = serviceUUID.getUuid();
            }
            bleDevice = new BluetoothLeDevice(macAddress, name, rssi, typeCommand, uuidService);
            queueScanning.add(bleDevice);
        }

        @Override
        public void execScanningQueue() throws RemoteException {
            status = "Scanning";
            if (queueScanning != null && !queueScanning.isEmpty() && mProcessing) {
                for (bleDevice = (BluetoothLeDevice) queueScanning.poll(); bleDevice != null; bleDevice = (BluetoothLeDevice) queueScanning.poll()) {
                    synchronized (bleDevice) {
                        int type = bleDevice.getType();
                        if (type == BluetoothLeDevice.SCANNING) {
                            //step scan new BLE devices
                            mScanning = true;
                            broadcastUpdate("Scanning bluetooth...");
                            Log.d(TAG, "Start scanning");
                            mBluetoothLeScanProcess.scanLeDevice(true);
                            mBluetoothLeScanProcess.setHandlerMessage(mHandlerMessage);
                        } else if (type == BluetoothLeDevice.FIND_LE_DEVICE) {
                            // step scan for known BLE devices
                            Log.d(TAG, "Start scanning for known BLE device ");
                            if (bleDevice.getMacAddress() != null) {
                                // find specific macAddress
                                mScanning = false;
                                broadcastUpdate("Searching device " + bleDevice.getMacAddress());
                                BluetoothDevice device = mBluetoothLeScanProcess.getRemoteDevice(bleDevice.getMacAddress());
                                if (device == null) {
                                    broadcastUpdate("Device " + bleDevice.getMacAddress() + "not found, try scanning...");
                                    mBluetoothLeScanProcess.findLeDevice(bleDevice.getMacAddress(), true);
                                    mBluetoothLeScanProcess.setHandlerMessage(mHandlerMessage);
                                    sleepThread(500);
                                    mBluetoothLeScanProcess.findLeDevice(bleDevice.getMacAddress(), false);
                                } else {
                                    mHandlerMessage.sendMessage(Message.obtain(mHandlerMessage, 0, 7, 0, device));
                                }
                            } else if (bleDevice.getServiceUUID() != null) {
                                // scan using specific service
                                mScanning = true;
                                UUID[] listBle = new UUID[1];
                                listBle[0] = bleDevice.getServiceUUID();
                                mBluetoothLeScanProcess.findLeDevice(listBle, true);
                                mBluetoothLeScanProcess.setHandlerMessage(mHandlerMessage);
                                sleepThread(1000);
                                mBluetoothLeScanProcess.findLeDevice(listBle, false);
                            }
                        } else if (type == BluetoothLeDevice.STOP_SCANNING) {
                            mScanning = false;
                            mBluetoothLeScanProcess.scanLeDevice(false);
                            Log.d(TAG, "Stop scanning...");
                            broadcastUpdate("Stop scanning bluetooth...");
                            broadcastUpdate("Found " + mBluetoothLeScanProcess.getScanResult().size() + " device(s)");
                        } else if (type == BluetoothLeDevice.STOP_SCAN) {
                            mScanning = false;
                            mBluetoothLeScanProcess.scanLeDevice(false);
                            Log.d(TAG, "Stop scanning...");
                            broadcastUpdate("Stop scanning...");
                        }
                    }
                }
            }
        }

        @Override
        public void doConnect(String macAddress) throws RemoteException {
            status = "Connecting";
            final BluetoothDevice device = mBluetoothLeScanProcess.getRemoteDevice(macAddress);
            synchronized (lock) {
                broadcastUpdate("\n");
                broadcastUpdate("connecting to " + device.getAddress());
                BluetoothLeGattCallback gattCallback = new BluetoothLeGattCallback(context, device);
                gattCallback.setHandlerMessage(mHandlerMessage);
                mBluetoothGatt = gattCallback.connect();
                Log.d(TAG, "connect to " + mBluetoothGatt.getDevice().getAddress() + " on " + mBinder.getPid());
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void doConnecting(String macAddress) throws RemoteException {
            status = "Connecting";
            final BluetoothDevice device = mBluetoothLeScanProcess.getRemoteDevice(macAddress);
            synchronized (device) {
                BluetoothLeGattCallback gattCallback = new BluetoothLeGattCallback(context, device);
                gattCallback.setHandlerMessage(mHandlerMessage);
                mBluetoothGatt = gattCallback.connect();
                Log.d(TAG, "connect to " + mBluetoothGatt.getDevice().getAddress() + " on " + mBinder.getPid());
            }
        }

        @Override
        public void doConnected(PBluetoothGatt gatt) {
            synchronized (lock) {
                try {
                    status = "Connected";
                    mBluetoothGatt = gatt.getGatt();
                    mBinder.broadcastUpdate("connected to " + mBluetoothGatt.getDevice().getAddress());
                    mBinder.broadcastUpdate("discovering services...");
                    status = "Discovering";
                    mBluetoothGatt.discoverServices();
                    lock.notifyAll();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void doDisconnected(PBluetoothGatt gatt, String type) throws RemoteException {
            mBluetoothGatt = gatt.getGatt();
            status = "Disconnected";
            synchronized (lock) {
                if (type.equals("GatewayService")) {
                    broadcastUpdate("Disconnected from " + mBluetoothGatt.getDevice().getAddress());
                    lock.notifyAll();
                } else {
                    try {
                        mBluetoothGatt.disconnect();
                        mBluetoothGatt.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }

        }

        @Override
        public void addQueueCharacteristic(PBluetoothGatt gatt, ParcelUuid serviceUUID, ParcelUuid characteristicUUID, ParcelUuid descriptorUUID, byte[] data, int typeCommand) throws RemoteException {
            mBluetoothGatt = gatt.getGatt();
            UUID uuidService = null;
            UUID uuidCharacteristic = null;
            UUID uuidDescriptor = null;
            if (serviceUUID != null) {
                uuidService = serviceUUID.getUuid();
            }
            if (characteristicUUID != null) {
                uuidCharacteristic = characteristicUUID.getUuid();
            }
            if (descriptorUUID != null) {
                uuidDescriptor = descriptorUUID.getUuid();
            }
            bleGatt = new BluetoothLeGatt(mBluetoothGatt, uuidService, uuidCharacteristic, uuidDescriptor, data, typeCommand);
            queueCharacteristic.add(bleGatt);
        }

        @Override
        public void execCharacteristicQueue() throws RemoteException {
            status = "Reading";
            broadcastUpdate("\n");
            if (queueCharacteristic != null && !queueCharacteristic.isEmpty() && mProcessing) {
                for (bleGatt = (BluetoothLeGatt) queueCharacteristic.poll(); bleGatt != null; bleGatt = (BluetoothLeGatt) queueCharacteristic.poll()) {
                    synchronized (bleGatt) {
                        int type = bleGatt.getTypeCommand();
                        if (type == BluetoothLeGatt.READ) {
                            mBluetoothGattCallback = new BluetoothLeGattCallback(bleGatt.getGatt());
                            broadcastUpdate("Reading Characteristic " + GattDataLookUp.characteristicNameLookup(bleGatt.getCharacteristicUUID()));
                            mBluetoothGattCallback.readCharacteristic(bleGatt.getServiceUUID(), bleGatt.getCharacteristicUUID());
                        } else if (type == BluetoothLeGatt.REGISTER_NOTIFY) {
                            mBluetoothGattCallback = new BluetoothLeGattCallback(bleGatt.getGatt());
                            broadcastUpdate("Registering Notify Characteristic " + bleGatt.getCharacteristicUUID().toString());
                            mBluetoothGattCallback.writeDescriptorNotify(bleGatt.getServiceUUID(), bleGatt.getCharacteristicUUID(), bleGatt.getDescriptorUUID());
                        } else if (type == BluetoothLeGatt.REGISTER_INDICATE) {
                            mBluetoothGattCallback = new BluetoothLeGattCallback(bleGatt.getGatt());
                            broadcastUpdate("Registering Indicate Characteristic " + bleGatt.getCharacteristicUUID().toString());
                            mBluetoothGattCallback.writeDescriptorIndication(bleGatt.getServiceUUID(), bleGatt.getCharacteristicUUID(), bleGatt.getDescriptorUUID());
                        } else if (type == BluetoothLeGatt.WRITE) {
                            mBluetoothGattCallback = new BluetoothLeGattCallback(bleGatt.getGatt());
                        }

                    }
                }

            }
        }

        @Override
        public BluetoothDevice getDevice(String macAddress) throws RemoteException {
            if (scanResults != null && scanResults.size() > 0) {
                List<BluetoothDevice> devices = null;
                try {
                    devices = mBinder.getScanResults();
                    for (BluetoothDevice device : devices) {
                        if (device.getAddress().equals(macAddress)) {
                            return device;
                        }
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
            return null;
        }

        @Override
        public void insertDatabaseDevice(BluetoothDevice device, int rssi, String deviceState) throws RemoteException {
            broadcastUpdate("Write device " + device.getAddress() + " to database");
            String deviceName = "unknown";
            if (device.getName() != null) {
                deviceName = device.getName();
            }
            bleDeviceDatabase.insertData(device.getAddress(), deviceName, rssi, deviceState);
        }

        @Override
        public void updateDatabaseDevice(BluetoothDevice device, int rssi, byte[] scanRecord) throws RemoteException {
            String deviceName = "unknown";
            if (device.getName() != null) {
                deviceName = device.getName();
            }
            try {
                bleDeviceDatabase.updateData(device.getAddress(), deviceName, rssi, null, scanRecord);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public boolean updateDatabaseService(String macAddress, String serviceUUID) throws RemoteException {
            return bleServicesDatabase.insertData(macAddress, serviceUUID);
        }

        @Override
        public boolean updateDatabaseCharacteristics(String macAddress, String serviceUUID, String characteristicUUID, String property, String value) throws RemoteException {
            return bleCharacteristicDatabase.insertData(macAddress, serviceUUID, characteristicUUID, property, value);
        }

        @Override
        public void updateDatabaseDeviceState(BluetoothDevice device, String deviceState) throws RemoteException {
            try {
                bleDeviceDatabase.updateDeviceState(device.getAddress(), deviceState);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void updateDatabaseDeviceAdvRecord(BluetoothDevice device, byte[] scanRecord) throws RemoteException {
            try {
                bleDeviceDatabase.updateDeviceAdvData(device.getAddress(), scanRecord);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void updateDatabaseDeviceUsrChoice(String macAddress, String userChoice) throws RemoteException {
            try {
                bleDeviceDatabase.updateDeviceUserChoice(macAddress, userChoice);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void updateDatabaseDevicePowerUsage(String macAddress, long powerUsage) throws RemoteException {
            try {
                bleDeviceDatabase.updateDevicePowerUsage(macAddress, powerUsage);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void updateAllDeviceStates(List<String> nearbyDevices) throws RemoteException {
            broadcastUpdate("\n");
            broadcastUpdate("Refresh all device states...");
            try {
                bleDeviceDatabase.updateAllDevicesState(nearbyDevices, "active");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public boolean checkDevice(String macAddress) throws RemoteException {
            if (macAddress == null) {
                return bleDeviceDatabase.isDeviceExist();
            }
            return bleDeviceDatabase.isDeviceExist(macAddress);
        }

        @Override
        public List<String> getListDevices() throws RemoteException {
            return bleDeviceDatabase.getListDevices();
        }

        @Override
        public List<String> getListActiveDevices() throws RemoteException {
            return bleDeviceDatabase.getListActiveDevices();
        }

        @Override
        public int getDeviceRSSI(String macAddress) throws RemoteException {
            return bleDeviceDatabase.getDeviceRssi(macAddress);
        }

        @Override
        public byte[] getDeviceScanRecord(String macAddress) throws RemoteException {
            return bleDeviceDatabase.getDeviceScanRecord(macAddress);
        }

        @Override
        public String getDeviceUsrChoice(String macAddress) throws RemoteException {
            return bleDeviceDatabase.getDeviceUsrChoice(macAddress);
        }

        @Override
        public String getDeviceState(String macAddress) throws RemoteException {
            return bleDeviceDatabase.getDeviceState(macAddress);
        }

        @Override
        public long getDevicePowerUsage(String macAddress) throws RemoteException {
            return bleDeviceDatabase.getDevicePowerUsage(macAddress);
        }

        @Override
        public List<ParcelUuid> getServiceUUIDs(String macAddress) throws RemoteException {
            return bleServicesDatabase.getServiceUUIDs(macAddress);
        }

        @Override
        public List<ParcelUuid> getCharacteristicUUIDs(String macAddress) throws RemoteException {
            return null;
        }

        @Override
        public void disconnectSpecificGatt(String macAddress) throws RemoteException {
            for (BluetoothGatt gatt : listBluetoothGatt) {
                if (gatt.getDevice().getAddress().equals(macAddress)) {
                    gatt.disconnect();
                    gatt.close();
                }
            }
        }

        @Override
        public void broadcastUpdate(String message) throws RemoteException {
            if (mProcessing) {
                final Intent intent = new Intent(GatewayService.MESSAGE_COMMAND);
                intent.putExtra("command", message);
                sendBroadcast(intent);
            }
        }
    };

    /**
     * Some routines section
     */

    private void setWakeLock() {
        powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WakeLockService");
        if ((wakeLock != null) && (!wakeLock.isHeld())) {
            wakeLock.acquire();
        }
    }


    public void sleepThread(long time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void disconnect() {
        mProcessing = false;
        mScanning = false;
        disconnectGatt();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        stopService(mIntent);
        stopSelf();
    }

    private void disconnectGatt() {
        for (BluetoothGatt gatt : listBluetoothGatt) {
            gatt.disconnect();
            gatt.close();
        }
    }

    private void broadcastUpdate(String message) {
        if (mProcessing) {
            final Intent intent = new Intent(GatewayService.MESSAGE_COMMAND);
            intent.putExtra("command", message);
            sendBroadcast(intent);
        }
    }

}
