/*******************************************************************************
 * Created by Orlando Aliaga
 * Copyright 2015 Prey Inc. All rights reserved.
 * License: GPLv3
 * Full license at "/LICENSE"
 ******************************************************************************/
package com.prey.actions.location;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.support.v4.app.ActivityCompat;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.prey.PreyLogger;
import com.prey.PreyPhone;
import com.prey.PreyPhone.Wifi;
import com.prey.actions.HttpDataService;
import com.prey.exceptions.PreyException;
import com.prey.json.UtilJson;
import com.prey.managers.PreyWifiManager;
import com.prey.net.PreyWebServices;
import com.prey.services.LocationService;

public class LocationUtil {

    public static final String LAT = "lat";
    public static final String LNG = "lng";
    public static final String ACC = "accuracy";
    public static final String METHOD = "method";

    public static HttpDataService dataLocation(Context ctx) {
        HttpDataService data =null;
        try {
            LocationManager mlocManager = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            boolean isGpsEnabled = mlocManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean isNetworkEnabled = mlocManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
            boolean isWifiEnabled=PreyWifiManager.getInstance(ctx).isWifiEnabled();
            PreyLogger.d("gps status:" + isGpsEnabled);
            PreyLogger.d("net status:" + isNetworkEnabled);
            PreyLogger.d("wifi status:" + isWifiEnabled);
            PreyLocation location = null;

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)) {
                if (isGpsEnabled || isNetworkEnabled) {
                    String method = getMethod(isGpsEnabled, isNetworkEnabled);
                    PreyLocation locationPlay = null;
                    int resultCode = GooglePlayServicesUtil.isGooglePlayServicesAvailable(ctx);
                    if (ConnectionResult.SUCCESS == resultCode) {
                        location = getPreyLocationPlayService(ctx, method);
                    }
                }
            }else{
                PreyLogger.d("ask for permission location");
            }
            if(location==null && isWifiEnabled)
                location = getDataLocationWifi(ctx);
            if(location!=null){
                PreyLogger.d("locationData:" + location.getLat()+" "+location.getLng()+" "+location.getAccuracy());
                data=convertData(location);
            }else{
                sendNotify(ctx,"Error","failed");
            }
        } catch (Exception e) {
            sendNotify(ctx,"Error");
        }
        return data;
    }

    private static String getMethod(boolean isGpsEnabled,boolean isNetworkEnabled){
        if (isGpsEnabled && isNetworkEnabled) {
            return "native";
        }
        if (isGpsEnabled) {
            return "gps";
        }
        if (isNetworkEnabled) {
            return "network";
        }
        return "";
    }
    private static void sendNotify(Context ctx,String message){
        Map<String, String> parms = UtilJson.makeMapParam("get", "location", "failed", message);
        PreyWebServices.getInstance().sendNotifyActionResultPreyHttp(ctx, parms);
    }

    private static void sendNotify(Context ctx,String message,String status){
        Map<String, String> parms = UtilJson.makeMapParam("get", "location", status, message);
        PreyWebServices.getInstance().sendNotifyActionResultPreyHttp(ctx, parms);
    }

    public static PreyLocation getDataLocationWifi(Context ctx) throws Exception {
        PreyLocation location = null;
        List<Wifi> listWifi = null;
        PreyPhone preyPhone = new PreyPhone(ctx);
        if (PreyWifiManager.getInstance(ctx).isWifiEnabled()) {
            listWifi = preyPhone.getListWifi();
        }
        if(listWifi==null||listWifi.size()==0){
            throw new Exception();
        }
        location = PreyWebServices.getInstance().getLocation(ctx, listWifi);
        return location;
    }

    public static PreyLocation getPreyLocationPlayService(final Context ctx,String method) throws Exception {
        final PreyGooglePlayServiceLocation play = new PreyGooglePlayServiceLocation();
        new Thread( new Runnable() {
            @Override
            public void run() {
                play.init(ctx);
            }
        }).start();


        int i=0;
        Location currentLocation = null;
        while (currentLocation == null&&i<6) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
            }
            currentLocation = play.getLastLocation(ctx);
            i=i+1;
        }
        PreyLocation preyLocation=null;
        if(currentLocation!=null){
            preyLocation = new PreyLocation(currentLocation,method);
        }else{
            if(currentLocation==null){
                preyLocation = getPreyLocationAppService(ctx,method);
            }
            if(currentLocation==null){
                preyLocation = getDataLocationWifi(ctx);
            }
        }
        return preyLocation;
    }

    public static PreyLocation getPreyLocationAppService(Context ctx,String method) throws Exception {
        PreyLocation location = null;
        Intent intent = new Intent(ctx, LocationService.class);
        try {
            ctx.startService(intent);
            boolean validLocation = false;
            int i = 0;
            while (!validLocation) {
                location = PreyLocationManager.getInstance(ctx).getLastLocation();
                if (location.isValid()) {
                    validLocation = true;
                } else {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        throw new PreyException("Thread was intrrupted. Finishing Location NotifierAction", e);
                    }
                    if (i > 2) {
                        return null;
                    }
                    i++;
                }
                location.setMethod(method);
            }
            ctx.stopService(intent);
        } catch (Exception e) {
            Map<String, String> parms = UtilJson.makeMapParam("get", "location", "failed", e.getMessage());
            PreyWebServices.getInstance().sendNotifyActionResultPreyHttp(ctx, parms);
        } finally {
            ctx.stopService(intent);
        }
        return location;
    }

    public static HttpDataService convertData(PreyLocation lastLocation) {
        if(lastLocation==null)
            return null;
        HttpDataService data = new HttpDataService("location");
        data.setList(true);
        HashMap<String, String> parametersMap = new HashMap<String, String>();
        parametersMap.put(LAT, Double.toString(lastLocation.getLat()));
        parametersMap.put(LNG, Double.toString(lastLocation.getLng()));
        parametersMap.put(ACC, Float.toString(lastLocation.getAccuracy()));
        parametersMap.put(METHOD, lastLocation.getMethod() );
        data.addDataListAll(parametersMap);
        return data;
    }

    public static PreyLocation dataPreyLocation(Context ctx) {
        HttpDataService data=dataLocation(ctx);
        PreyLocation location=new PreyLocation();
        location.setLat(Double.parseDouble(data.getDataList().get(LAT)));
        location.setLng(Double.parseDouble(data.getDataList().get(LNG)));
        location.setAccuracy(Float.parseFloat(data.getDataList().get(ACC)));
        return location;
    }
}