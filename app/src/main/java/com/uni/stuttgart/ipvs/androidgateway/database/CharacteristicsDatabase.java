package com.uni.stuttgart.ipvs.androidgateway.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by mdand on 3/23/2018.
 */

public class CharacteristicsDatabase extends SQLiteOpenHelper {

    public static final String DATABASE_NAME = "BleCharacteristicsData.db";
    public static final String ID = "id";
    public static final String MAC_ADDRESS = "mac_address";
    public static final String SERVICE_UUID = "service_uuid";
    public static final String CHARACRERISTIC_UUID = "characteristic_uuid";
    public static final String CHARACRERISTIC_PROPERTY = "characteristic_property";
    public static final String CHARACRERISTIC_VALUE = "characteristic_value";
    public static final String CREATE_DATE = "create_date";
    public static final String MODIFIED_DATE = "modified_date";

    public CharacteristicsDatabase(Context context) {
        super(context, DATABASE_NAME , null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "create table if not exists BleCharacteristicsData " +
                        "(id integer primary key, mac_address text, service_uuid text, characteristic_uuid text, characteristic_property text, characteristic_value text, create_date text, modified_date text)"
        );
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS BleCharacteristicsData");
        onCreate(db);
    }

    public boolean insertData(String macAddress, String serviceUUID, String characteristicUUID, String characteristicProperty, String charcteristicValue) {
        boolean status = false;
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            ContentValues contentValues = new ContentValues();
            contentValues.put("mac_address", macAddress);
            contentValues.put("service_uuid", serviceUUID);
            contentValues.put("characteristic_uuid", characteristicUUID);
            contentValues.put("characteristic_property", characteristicProperty);
            contentValues.put("characteristic_value", charcteristicValue);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd G 'at' HH:mm:ss z");
            String date = sdf.format(new Date());
            contentValues.put("create_date", date);
            contentValues.put("modified_date", date);

            if(isServiceExist(serviceUUID) && isMacAddressExist(macAddress) && isCharacteristicExist(characteristicUUID)) {
                db.update("BleCharacteristicsData", contentValues, "mac_address = '" + macAddress + "' AND service_uuid = '" + serviceUUID +"' AND characteristic_uuid = '" + characteristicUUID + "'", null);
            } else {
                db.insert("BleCharacteristicsData", null, contentValues);
            }

            status = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return status;
    }

    public boolean deleteAllData() {
        SQLiteDatabase db = this.getWritableDatabase();
        db.execSQL("delete from BleCharacteristicsData ");
        db.close();
        return true;
    }

    public boolean isMacAddressExist(String key) {
        Cursor cursor = null;
        boolean status = false;
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            cursor = db.rawQuery("SELECT mac_address from BleCharacteristicsData WHERE mac_address=?", new String[] {key + ""});
            if(cursor.getCount() > 0) {
                status = true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            cursor.close();
        }

        return status;
    }

    public boolean isServiceExist(String key) {
        Cursor cursor = null;
        boolean status = false;
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            cursor = db.rawQuery("SELECT service_uuid from BleCharacteristicsData WHERE service_uuid=?", new String[] {key + ""});
            if(cursor.getCount() > 0) {
                status = true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            cursor.close();
        }

        return status;
    }

    public boolean isCharacteristicExist(String key) {
        Cursor cursor = null;
        boolean status = false;
        try {
            SQLiteDatabase db = this.getWritableDatabase();
            cursor = db.rawQuery("SELECT characteristic_uuid from BleCharacteristicsData WHERE characteristic_uuid=?", new String[] {key + ""});
            if(cursor.getCount() > 0) {
                status = true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            cursor.close();
        }

        return status;
    }

    public Map<Integer, Map<String, Date>>  getAllData() {
        Map<Integer, Map<String, Date>> mapResult = new HashMap<>();
        Map<String, Date> mapData = new HashMap<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor res =  db.rawQuery( "select * from BleCharacteristicsData", null );
        res.moveToFirst();

        while(res.isAfterLast() == false){
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd G 'at' HH:mm:ss z");
            Date date = null;
            try {
                date = sdf.parse(res.getString(res.getColumnIndex(CREATE_DATE)));
                String characteristicUUID = res.getString(res.getColumnIndex(CHARACRERISTIC_UUID));
                String serviceUUID = res.getString(res.getColumnIndex(SERVICE_UUID));
                String characteristicValue = res.getString(res.getColumnIndex(CHARACRERISTIC_VALUE));
                String characteristicProperty = res.getString(res.getColumnIndex(CHARACRERISTIC_PROPERTY));
                int key = res.getInt(res.getColumnIndex(ID));
                mapData.put(characteristicUUID, date);
                mapResult.put(key, mapData);
            } catch (ParseException e) {
                e.printStackTrace();
            }
        }
        return mapResult;
    }
}
