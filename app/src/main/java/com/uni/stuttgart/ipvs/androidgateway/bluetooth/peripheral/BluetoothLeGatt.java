package com.uni.stuttgart.ipvs.androidgateway.bluetooth.peripheral;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.UUID;

/**
 * Created by mdand on 2/28/2018.
 */

public class BluetoothLeGatt {

    public static int READ = 100;
    public static int REGISTER_NOTIFY = 101;
    public static int REGISTER_INDICATE = 102;
    public static int READ_DESCRIPTOR = 103;
    public static int READ_ENCRYPTED = 103;
    public static int WRITE = 200;
    public static int WRITE_DESCRIPTOR = 201;

    private BluetoothGatt gatt;
    private UUID serviceUUID;
    private UUID characteristicUUID;
    private UUID descriptorUUID;
    private byte[] data;
    private int typeCommand;
    private String jsonData;

    public BluetoothLeGatt(){}

    public BluetoothLeGatt(BluetoothGatt gatt, UUID serviceUUID, UUID characteristicUUID, UUID descriptorUUID, byte[] data, int typeCommand) {
        this.gatt = gatt;
        this.serviceUUID = serviceUUID;
        this.characteristicUUID = characteristicUUID;
        this.descriptorUUID = descriptorUUID;
        this.data = data;
        this.typeCommand = typeCommand;
    }

    public BluetoothGatt getGatt() {return gatt;}

    public void setGatt(BluetoothGatt gatt) {this.gatt = gatt;}

    public UUID getServiceUUID() {
        return serviceUUID;
    }

    public void setServiceUUID(UUID serviceUUID) {
        this.serviceUUID = serviceUUID;
    }

    public UUID getCharacteristicUUID() {
        return characteristicUUID;
    }

    public void setCharacteristicUUID(UUID characteristicUUID) { this.characteristicUUID = characteristicUUID; }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public int getTypeCommand() {return typeCommand;}

    public void setTypeCommand(int typeCommand) {
        this.typeCommand = typeCommand;
    }

    public void setJsonData(String jsonData) {this.jsonData = jsonData;}

    public String getJsonData() {return this.jsonData;}

    public UUID getDescriptorUUID() { return descriptorUUID; }

    public void setDescriptorUUID(UUID descriptorUUID) { this.descriptorUUID = descriptorUUID; }

}
