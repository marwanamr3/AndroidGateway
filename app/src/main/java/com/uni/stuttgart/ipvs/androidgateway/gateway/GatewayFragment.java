package com.uni.stuttgart.ipvs.androidgateway.gateway;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.google.android.gms.location.LocationRequest;
import com.uni.stuttgart.ipvs.androidgateway.R;
import com.uni.stuttgart.ipvs.androidgateway.database.BleDeviceDatabase;
import com.uni.stuttgart.ipvs.androidgateway.database.CharacteristicsDatabase;
import com.uni.stuttgart.ipvs.androidgateway.database.ServicesDatabase;
import com.uni.stuttgart.ipvs.androidgateway.helper.BroadcastReceiverHelper;

public class GatewayFragment extends Fragment {
    private BluetoothAdapter mBluetoothAdapter;
    private LocationRequest mLocation;
    private Intent mGatewayService;
    private EditText textArea;
    private Menu menuBar;
    private Context context;

    private boolean mProcessing = false;
    private GatewayController mService;
    private boolean mBound;

    private PowerManager.WakeLock wakeLock;
    private PowerManager powerManager;

    private BleDeviceDatabase bleDeviceDatabase;
    private ServicesDatabase bleServicesDatabase;
    private CharacteristicsDatabase bleCharacteristicDatabase;

    private BroadcastReceiverHelper mBReceiver = new BroadcastReceiverHelper();

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        this.context = context;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_gateway, container, false);
        setHasOptionsMenu(true);

        mLocation = LocationRequest.create();
        textArea = (EditText) view.findViewById(R.id.textArea);
        textArea.setFocusable(false);
        textArea.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                switch (event.getAction() & MotionEvent.ACTION_MASK) {
                    case MotionEvent.ACTION_UP:
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        break;
                }
                return false;
            }
        });

        powerManager = (PowerManager) getActivity().getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WakeLockGateway");

        setWakeLock();
        checkingPermission();
        registerBroadcastListener();
        clearDatabase();

        // Ensures Bluetooth is enabled on the device.  If Bluetooth is not currently enabled,
        // fire an intent to display a dialog asking the user to grant permission to enable it.
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, 1);
        } else {
            startServiceGateway();
        }

        return view;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        // Inflate the menu; this adds items to the action bar if it is present.
        inflater.inflate(R.menu.menu_gateway, menu);
        menuBar = menu;
        setMenuVisibility();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        switch (id) {
            case R.id.action_start:
                startServiceGateway();
                setMenuVisibility();
                break;
            case R.id.action_stop:
                stopServiceGateway();
                //scheduler.shutdown();
                setMenuVisibility();
                break;
        }
        return true;
    }

    @Override
    public void onStart() {
        super.onStart();
        setWakeLock();
    }

    @Override
    public void onStop() {
        super.onStop();
        setWakeLock();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopServiceGateway();
        if(wakeLock != null && wakeLock.isHeld()) {wakeLock.release();}
        if(mReceiver != null) {getActivity().unregisterReceiver(mReceiver);}
        getActivity().finish();
    }

    @Override
    public void onResume() {
        super.onResume();
        setWakeLock();
    }

    @Override
    public void onPause() {
        super.onPause();
        setWakeLock();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        // User chose not to enable Bluetooth.
        if (requestCode == 1 && resultCode == Activity.RESULT_CANCELED) {
            getActivity().finish();
            return;
        }

        startServiceGateway();

        super.onActivityResult(requestCode, resultCode, data);
    }

    private void setWakeLock() {
        if((wakeLock != null) && (!wakeLock.isHeld())) { wakeLock.acquire(); }
    }

    /**
     * Start and Stop Gateway Controller Section
     */

    private void startServiceGateway() {
        mGatewayService = new Intent(context, GatewayController.class);
        getActivity().startService(mGatewayService);
        setCommandLine("\n");
        setCommandLine("Start Services...");
        mProcessing = true;
    }

    private void stopServiceGateway() {
        if(mGatewayService != null) {getActivity().stopService(mGatewayService); }
        setCommandLine("\n");
        setCommandLine("Stop Services...");
        mProcessing = false;
    }

    private void setMenuVisibility() {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mProcessing) {
                    menuBar.findItem(R.id.menu_refresh_gateway).setActionView(R.layout.action_interdeminate_progress);
                    menuBar.findItem(R.id.action_start).setVisible(false);
                    menuBar.findItem(R.id.action_stop).setVisible(true);
                } else {
                    menuBar.findItem(R.id.menu_refresh_gateway).setActionView(null);
                    menuBar.findItem(R.id.action_start).setVisible(true);
                    menuBar.findItem(R.id.action_stop).setVisible(false);
                }
            }
        });
    }

    private void checkingPermission() {
        // Use this check to determine whether BLE is supported on the device.  Then you can
        // selectively disable BLE-related features.
        setCommandLine("Start checking permissions...");
        if (!getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(context, "Ble is not supported", Toast.LENGTH_SHORT).show();
            getActivity().finish();
        }

        // Initializes a Bluetooth adapter.  For API level 18 and above, get a reference to
        // BluetoothAdapter through BluetoothManager.
        final BluetoothManager bluetoothManager =
                (BluetoothManager) getActivity().getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();

        // Checks if Bluetooth is supported on the device.
        if (bluetoothManager.getAdapter() == null) {
            Toast.makeText(context, "Bluetooth is not supported", Toast.LENGTH_SHORT).show();
            getActivity().finish();
        }

        // check if location is enabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            /** force user to turn on location service */
            if (getActivity().checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(context, "Please turn on Location Permission!", Toast.LENGTH_LONG).show();
                getActivity().finish();
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (getActivity().checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(context, "Please turn on Storage Permission!", Toast.LENGTH_LONG).show();
                getActivity().finish();
            }
        }

        setCommandLine("Checking permissions done...");
    }

    /**
     * Broadcast Listener & Receiver Section
     */

    private void registerBroadcastListener() {
        IntentFilter filter1 = new IntentFilter(GatewayService.MESSAGE_COMMAND);
        getActivity().registerReceiver(mReceiver, filter1);

        IntentFilter filter2 = new IntentFilter(GatewayService.TERMINATE_COMMAND);
        getActivity().registerReceiver(mReceiver, filter2);

        IntentFilter filter3 = new IntentFilter(GatewayService.START_COMMAND);
        getActivity().registerReceiver(mReceiver, filter3);

        IntentFilter pairingRequestFilter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
        pairingRequestFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY - 1);
        getActivity().registerReceiver(mBReceiver, pairingRequestFilter);

        setCommandLine("Start Broadcast Listener...");
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (action.equals(GatewayService.MESSAGE_COMMAND)) {
                String message = intent.getStringExtra("command");
                setCommandLine(message);
            } else if (action.equals(GatewayService.TERMINATE_COMMAND)) {
                String message = intent.getStringExtra("command");
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                stopServiceGateway();
                setMenuVisibility();
            } else if (action.equals(GatewayService.START_COMMAND)) {
                if(!mProcessing) {
                    String message = intent.getStringExtra("command");
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                    if (mBluetoothAdapter != null && mBluetoothAdapter.isEnabled()) {
                        startServiceGateway();
                    }
                }
            }
        }

    };

    /**
     * Other Routines Section
     */

    private void clearDatabase() {
        bleDeviceDatabase = new BleDeviceDatabase(context);
        bleServicesDatabase = new ServicesDatabase(context);
        bleCharacteristicDatabase = new CharacteristicsDatabase(context);
        
        bleDeviceDatabase.deleteAllData();
        bleServicesDatabase.deleteAllData();
        bleCharacteristicDatabase.deleteAllData();
    }

    /**
     * View Related Routine Section
     */

    private void setCommandLine(final String info) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textArea.append("\n");
                textArea.append(info);
            }
        });
    }


}