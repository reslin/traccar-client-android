/*
 * Copyright 2012 - 2017 Anton Tananaev (anton.tananaev@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.client;

import android.Manifest;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.preference.TwoStatePreference;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.webkit.URLUtil;
import android.widget.Toast;

import java.util.Random;

public class MainFragment extends PreferenceFragment implements OnSharedPreferenceChangeListener {

    private static final String TAG = MainFragment.class.getSimpleName();

    private static final int ALARM_MANAGER_INTERVAL = 15000;

    public static final String KEY_DEVICE = "id";
    public static final String KEY_URL = "url";
    public static final String KEY_INTERVAL = "interval";
    public static final String KEY_DISTANCE = "distance";
    public static final String KEY_ANGLE = "angle";
    public static final String KEY_ACCURACY = "accuracy";
    public static final String KEY_STATUS = "status";

    //possible preferences to change in a remote-config request
    public static final String[] PREFS_KEYS = {KEY_DEVICE, KEY_URL, KEY_INTERVAL, KEY_DISTANCE, KEY_ANGLE, KEY_ACCURACY};

    private static final int PERMISSIONS_REQUEST_LOCATION = 2;

    private SharedPreferences sharedPreferences;

    private Preference mPrefDevice, mPrefUrl, mPrefInterval, mPrefDistance, mPrefAngle, mPrefAccuracy;

    private AlarmManager alarmManager;
    private PendingIntent alarmIntent;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (BuildConfig.HIDDEN_APP) {
            removeLauncherIcon();
        }

        setHasOptionsMenu(true);

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getActivity());
        addPreferencesFromResource(R.xml.preferences);

        mPrefDevice = findPreference(KEY_DEVICE);
        mPrefUrl = findPreference(KEY_URL);
        mPrefInterval = findPreference(KEY_INTERVAL);
        mPrefDistance = findPreference(KEY_DISTANCE);
        mPrefAngle = findPreference(KEY_ANGLE);
        mPrefAccuracy = findPreference(KEY_ACCURACY);

        initPreferences();

        mPrefDevice.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                if (newValue != null && !newValue.equals("")) {
                    mPrefDevice.setSummary(newValue.toString());
                    return true;
                }
                return false;
            }
        });
        mPrefUrl.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                if (newValue != null && validateServerURL(newValue.toString())) {
                    mPrefUrl.setSummary(newValue.toString());
                    return true;
                }
                return false;
            }
        });

        Preference.OnPreferenceChangeListener numberValidationListener = new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                if (newValue != null) {
                    try {
                        int value = Integer.parseInt((String) newValue);
                        preference.setSummary(newValue.toString());
                        return value >= 0;
                    } catch (NumberFormatException e) {
                        Log.w(TAG, e);
                    }
                }
                return false;
            }
        };

        mPrefInterval.setOnPreferenceChangeListener(numberValidationListener);
        mPrefDistance.setOnPreferenceChangeListener(numberValidationListener);
        mPrefAngle.setOnPreferenceChangeListener(numberValidationListener);
        mPrefAccuracy.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                if (newValue != null && (newValue.equals("high") || newValue.equals("medium") || newValue.equals("low"))) {
                    mPrefAccuracy.setSummary(newValue.toString());
                    Toast.makeText(getActivity(), "New acc: " + newValue.toString(), Toast.LENGTH_LONG).show();
                    return true;
                }
                return false;
            }
        });

        processConfigIntent();

        alarmManager = (AlarmManager) getActivity().getSystemService(Context.ALARM_SERVICE);
        alarmIntent = PendingIntent.getBroadcast(getActivity(), 0, new Intent(getActivity(), AutostartReceiver.class), 0);

        if (sharedPreferences.getBoolean(KEY_STATUS, false)) {
            startTrackingService(true, false);
        }
    }

    private void removeLauncherIcon() {
        String className = MainActivity.class.getCanonicalName().replace(".MainActivity", ".Launcher");
        ComponentName componentName = new ComponentName(getActivity().getPackageName(), className);
        PackageManager packageManager = getActivity().getPackageManager();
        if (packageManager.getComponentEnabledSetting(componentName) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            packageManager.setComponentEnabledSetting(
                componentName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);

            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setIcon(android.R.drawable.ic_dialog_alert);
            builder.setMessage(getString(R.string.hidden_alert));
            builder.setPositiveButton(android.R.string.ok, null);
            builder.show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
    }

    private void setPreferencesEnabled(boolean enabled) {
        mPrefDevice.setEnabled(enabled);
        mPrefUrl.setEnabled(enabled);
        mPrefInterval.setEnabled(enabled);
        mPrefDistance.setEnabled(enabled);
        mPrefAngle.setEnabled(enabled);
        mPrefAccuracy.setEnabled(enabled);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key.equals(KEY_STATUS)) {
            if (sharedPreferences.getBoolean(KEY_STATUS, false)) {
                startTrackingService(true, false);
            } else {
                stopTrackingService();
            }
        } else if (key.equals(KEY_DEVICE)) {
            mPrefDevice.setSummary(sharedPreferences.getString(KEY_DEVICE, null));
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.main, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.status) {
            startActivity(new Intent(getActivity(), StatusActivity.class));
            return true;
        } else if (item.getItemId() == R.id.about) {
            startActivity(new Intent(getActivity(), AboutActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void initPreferences() {
        PreferenceManager.setDefaultValues(getActivity(), R.xml.preferences, false);

        if (!sharedPreferences.contains(KEY_DEVICE)) {
            String id = String.valueOf(new Random().nextInt(900000) + 100000);
            sharedPreferences.edit().putString(KEY_DEVICE, id).apply();
            ((EditTextPreference) findPreference(KEY_DEVICE)).setText(id);
        }
        mPrefDevice.setSummary(sharedPreferences.getString(KEY_DEVICE, null));
        mPrefUrl.setSummary(sharedPreferences.getString(KEY_URL, null));
        mPrefInterval.setSummary(sharedPreferences.getString(KEY_INTERVAL, null));
        mPrefDistance.setSummary(sharedPreferences.getString(KEY_DISTANCE, null));
        mPrefAngle.setSummary(sharedPreferences.getString(KEY_ANGLE, null));
        mPrefAccuracy.setSummary(sharedPreferences.getString(KEY_ACCURACY, null));
    }

    private void startTrackingService(boolean checkPermission, boolean permission) {
        if (checkPermission) {
            if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                permission = true;
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSIONS_REQUEST_LOCATION);
                }
                return;
            }
        }

        if (permission) {
            setPreferencesEnabled(false);
            ContextCompat.startForegroundService(getActivity(), new Intent(getActivity(), TrackingService.class));
            alarmManager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                ALARM_MANAGER_INTERVAL, ALARM_MANAGER_INTERVAL, alarmIntent);
        } else {
            sharedPreferences.edit().putBoolean(KEY_STATUS, false).apply();
            TwoStatePreference preference = (TwoStatePreference) findPreference(KEY_STATUS);
            preference.setChecked(false);
        }
    }

    private void stopTrackingService() {
        alarmManager.cancel(alarmIntent);
        getActivity().stopService(new Intent(getActivity(), TrackingService.class));
        setPreferencesEnabled(true);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST_LOCATION) {
            boolean granted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
            startTrackingService(false, granted);
        }
    }

    private boolean validateServerURL(String userUrl) {
        int port = Uri.parse(userUrl).getPort();
        if (URLUtil.isValidUrl(userUrl) && (port == -1 || (port > 0 && port <= 65535))
            && (URLUtil.isHttpUrl(userUrl) || URLUtil.isHttpsUrl(userUrl))) {
            return true;
        }
        Toast.makeText(getActivity(), R.string.error_msg_invalid_url, Toast.LENGTH_LONG).show();
        return false;
    }

    private void processConfigIntent() {
        Intent intent = getActivity().getIntent();
        if (intent != null) {
            Uri data = intent.getData();
            if (data != null && !data.getQueryParameterNames().isEmpty()) {
                String msg = "";
                boolean allNulls = true;
                final SharedPreferences.Editor editor = sharedPreferences.edit();
                for (String key : PREFS_KEYS) {
                    String val = data.getQueryParameter(key);
                    if (val != null) {
                        allNulls = false;
                        msg += key + ": " + val + "\n";
                        editor.putString(key, val);
                        findPreference(key).setSummary(val);
                    }
                }
                if (!allNulls) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
                    builder.setIcon(android.R.drawable.ic_dialog_alert)
                        .setTitle(R.string.dialog_title_settings_request)
                        .setMessage(getString(R.string.dialog_msg_announce) + msg)
                        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                editor.commit();
                                Toast.makeText(getActivity(), R.string.ok_settings_written, Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                }
            }
        }
    }
}
