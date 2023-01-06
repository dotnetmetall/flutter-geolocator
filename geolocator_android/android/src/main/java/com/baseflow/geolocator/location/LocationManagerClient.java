package com.baseflow.geolocator.location;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.baseflow.geolocator.errors.ErrorCallback;
import com.baseflow.geolocator.errors.ErrorCodes;

import java.util.ArrayList;
import java.util.List;

class LocationManagerClient implements LocationClient, LocationListener {

    private static final long ONE_MINUTE = 1000 * 60;
    private final LocationManager locationManager;
    private final NmeaClient nmeaClient;
    @Nullable
    private final LocationOptions locationOptions;
    public Context context;
    private boolean isListening = false;

    @Nullable
    private Location currentBestLocation;
    //    @Nullable
//    private String currentLocationProvider;
    @Nullable
    private PositionChangedCallback positionChangedCallback;
    @Nullable
    private ErrorCallback errorCallback;

    private ArrayList<String> providersForLocation = new ArrayList<String>();
    private int currentProviderIndex = -1;

    public LocationManagerClient(
            @NonNull Context context, @Nullable LocationOptions locationOptions) {
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        this.locationOptions = locationOptions;
        this.context = context;
        this.nmeaClient = new NmeaClient(context, locationOptions);
    }

    static boolean isBetterLocation(Location location, Location bestLocation) {
        if (bestLocation == null) return true;

        long timeDelta = location.getTime() - bestLocation.getTime();
        boolean isSignificantlyNewer = timeDelta > ONE_MINUTE;
        boolean isSignificantlyOlder = timeDelta < -ONE_MINUTE;
        boolean isNewer = timeDelta > 0;

        if (isSignificantlyNewer) return true;

        if (isSignificantlyOlder) return false;

        float accuracyDelta = (int) (location.getAccuracy() - bestLocation.getAccuracy());
        boolean isLessAccurate = accuracyDelta > 0;
        boolean isMoreAccurate = accuracyDelta < 0;
        boolean isSignificantlyLessAccurate = accuracyDelta > 200;

        boolean isFromSameProvider = false;
        if (location.getProvider() != null) {
            isFromSameProvider = location.getProvider().equals(bestLocation.getProvider());
        }

        if (isMoreAccurate) return true;

        if (isNewer && !isLessAccurate) return true;

        //noinspection RedundantIfStatement
        if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) return true;

        return false;
    }

    private static String getBestProvider(
            LocationManager locationManager, LocationAccuracy accuracy) {
        Criteria criteria = new Criteria();

        criteria.setBearingRequired(false);
        criteria.setAltitudeRequired(false);
        criteria.setSpeedRequired(false);

        switch (accuracy) {
            case lowest:
                criteria.setAccuracy(Criteria.NO_REQUIREMENT);
                criteria.setHorizontalAccuracy(Criteria.NO_REQUIREMENT);
                criteria.setPowerRequirement(Criteria.NO_REQUIREMENT);
                break;
            case low:
                criteria.setAccuracy(Criteria.ACCURACY_COARSE);
                criteria.setHorizontalAccuracy(Criteria.ACCURACY_LOW);
                criteria.setPowerRequirement(Criteria.NO_REQUIREMENT);
                break;
            case medium:
                criteria.setAccuracy(Criteria.ACCURACY_COARSE);
                criteria.setHorizontalAccuracy(Criteria.ACCURACY_MEDIUM);
                criteria.setPowerRequirement(Criteria.POWER_MEDIUM);
                break;
            default:
                criteria.setAccuracy(Criteria.ACCURACY_FINE);
                criteria.setHorizontalAccuracy(Criteria.ACCURACY_HIGH);
                criteria.setPowerRequirement(Criteria.POWER_HIGH);
                break;
        }

        String provider = locationManager.getBestProvider(criteria, true);

        if (provider.trim().isEmpty()) {
            List<String> providers = locationManager.getProviders(true);
            if (providers.size() > 0) provider = providers.get(0);
        }

        return provider;
    }

    private static float accuracyToFloat(LocationAccuracy accuracy) {
        switch (accuracy) {
            case lowest:
            case low:
                return 500;
            case medium:
                return 250;
            case best:
            case bestForNavigation:
                return 50;
            default:
                return 100;
        }
    }

    @Override
    public void isLocationServiceEnabled(LocationServiceListener listener) {
        if (locationManager == null) {
            listener.onLocationServiceResult(false);
            return;
        }

        listener.onLocationServiceResult(checkLocationService(context));
    }

    @Override
    public void getLastKnownPosition(
            PositionChangedCallback positionChangedCallback, ErrorCallback errorCallback) {
        Location bestLocation = null;

        for (String provider : locationManager.getProviders(true)) {
            @SuppressLint("MissingPermission")
            Location location = locationManager.getLastKnownLocation(provider);

            if (location != null && isBetterLocation(location, bestLocation)) {
                bestLocation = location;
            }
        }

        positionChangedCallback.onPositionChanged(bestLocation);
    }

    @Override
    public boolean onActivityResult(int requestCode, int resultCode) {
        return false;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void startPositionUpdates(
            Activity activity,
            PositionChangedCallback positionChangedCallback,
            ErrorCallback errorCallback) {

        if (!checkLocationService(context)) {
            errorCallback.onError(ErrorCodes.locationServicesDisabled);
            return;
        }

        this.positionChangedCallback = positionChangedCallback;
        this.errorCallback = errorCallback;

        this.prepareLocationProviders();

        if (this.currentProviderIndex < 0) {
            errorCallback.onError(ErrorCodes.locationServicesDisabled);
            return;
        }

        this.isListening = true;
        this.nmeaClient.start();
        this.requestLocationListening();
    }

    @SuppressLint("MissingPermission")
    @Override
    public void stopPositionUpdates() {
        this.isListening = false;
        this.nmeaClient.stop();
        this.locationManager.removeUpdates(this);
        this.providersForLocation.clear();
        this.currentProviderIndex = -1;
    }

    @Override
    public synchronized void onLocationChanged(Location location) {
        float desiredAccuracy =
                locationOptions != null ? accuracyToFloat(locationOptions.getAccuracy()) : 50;

        if (isBetterLocation(location, currentBestLocation)
                && location.getAccuracy() <= desiredAccuracy) {
            this.currentBestLocation = location;

            if (this.positionChangedCallback != null) {
                nmeaClient.enrichExtrasWithNmea(location);
                this.positionChangedCallback.onPositionChanged(currentBestLocation);
            }
        }
    }

    @TargetApi(28)
    @SuppressWarnings("deprecation")
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        if (status == android.location.LocationProvider.AVAILABLE) {
            onProviderEnabled(provider);
        } else if (status == android.location.LocationProvider.OUT_OF_SERVICE) {
            onProviderDisabled(provider);
        }
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onProviderEnabled(String provider) {
        int providerIndex = this.providersForLocation.indexOf(provider);
        if (providerIndex < 0) {
            return;
        }

        if (providerIndex >= this.currentProviderIndex) {
            return;
        }

        this.locationManager.removeUpdates(this);
        this.currentProviderIndex = providerIndex;
        requestLocationListening();
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onProviderDisabled(String provider) {
        String currentProvider = this.providersForLocation.get(this.currentProviderIndex);

        if (provider.equals(currentProvider)) {

            if (!isListening) {
                return;
            }

            this.locationManager.removeUpdates(this);
            this.currentProviderIndex++;

            if (currentProviderIndex >= this.providersForLocation.size()) {
                if (this.errorCallback != null) {
                    errorCallback.onError(ErrorCodes.locationServicesDisabled);
                }
                return;
            }

            String nextProvider = this.providersForLocation.get(this.currentProviderIndex);
            boolean isNextProviderEnabled = locationManager.isProviderEnabled(nextProvider);
            if (!isNextProviderEnabled) {
                if (this.errorCallback != null) {
                    errorCallback.onError(ErrorCodes.locationServicesDisabled);
                }
                return;
            }

            this.requestLocationListening();
        }
    }

    private void prepareLocationProviders() {
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            this.providersForLocation.add(LocationManager.GPS_PROVIDER);
        }

        LocationAccuracy locationAccuracy =
                this.locationOptions != null ? this.locationOptions.getAccuracy() : LocationAccuracy.best;
        String balancedLocationProvider = getBestProvider(this.locationManager, locationAccuracy);

        if (!this.providersForLocation.contains(balancedLocationProvider)) {
            this.providersForLocation.add(balancedLocationProvider);
        }

        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            this.providersForLocation.add(LocationManager.NETWORK_PROVIDER);
        }

        if (!this.providersForLocation.isEmpty()) {
            this.currentProviderIndex = 0;
        }
    }

    @SuppressLint("MissingPermission")
    private void requestLocationListening() {
        long timeInterval = 0;
        float distanceFilter = 0;
        if (this.locationOptions != null) {
            timeInterval = locationOptions.getTimeInterval();
            distanceFilter = locationOptions.getDistanceFilter();
        }

        String provider = this.providersForLocation.get(this.currentProviderIndex);
        this.locationManager.requestLocationUpdates(provider, timeInterval, distanceFilter, this);
    }
}
