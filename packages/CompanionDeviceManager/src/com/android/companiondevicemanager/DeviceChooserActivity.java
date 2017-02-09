/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.companiondevicemanager;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.companion.CompanionDeviceManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.DataSetObserver;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Html;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public class DeviceChooserActivity extends Activity {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "DeviceChooserActivity";

    private ListView mDeviceListView;
    private View mPairButton;
    private View mCancelButton;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (DEBUG) Log.i(LOG_TAG, "Started with intent " + getIntent());

        setContentView(R.layout.device_chooser);
        setTitle(Html.fromHtml(getString(R.string.chooser_title, getCallingAppName()), 0));
        getWindow().getDecorView().getRootView().setBackgroundColor(Color.LTGRAY); //TODO theme

        if (getService().mDevicesFound.isEmpty()) {
            Log.e(LOG_TAG, "About to show UI, but no devices to show");
        }

        final DeviceDiscoveryService.DevicesAdapter adapter = getService().mDevicesAdapter;
        mDeviceListView = (ListView) findViewById(R.id.device_list);
        mDeviceListView.setAdapter(adapter);
        mDeviceListView.addFooterView(getProgressBar(), null, false);

        mPairButton = findViewById(R.id.button_pair);
        mPairButton.setOnClickListener((view) ->
                onPairTapped(getService().mSelectedDevice));
        adapter.registerDataSetObserver(new DataSetObserver() {
            @Override
            public void onChanged() {
                updatePairButtonEnabled();
            }
        });
        updatePairButtonEnabled();
        mCancelButton = findViewById(R.id.button_cancel);
        mCancelButton.setOnClickListener((view) -> {
            setResult(RESULT_CANCELED);
            finish();
        });
    }

    private CharSequence getCallingAppName() {
        try {
            final PackageManager packageManager = getPackageManager();
            return packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(getService().mCallingPackage, 0));
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setTitle(CharSequence title) {
        final TextView titleView = (TextView) findViewById(R.id.title);
        final int padding = getPadding(getResources());
        titleView.setPadding(padding, padding, padding, padding);
        titleView.setText(title);
    }

    //TODO put in resources xmls
    private ProgressBar getProgressBar() {
        final ProgressBar progressBar = new ProgressBar(this);
        progressBar.setForegroundGravity(Gravity.CENTER_HORIZONTAL);
        final int padding = getPadding(getResources());
        progressBar.setPadding(padding, padding, padding, padding);
        return progressBar;
    }

    static int getPadding(Resources r) {
        return r.getDimensionPixelSize(R.dimen.padding);
        //TODO
//        final float dp = r.getDisplayMetrics().density;
//        return (int)(12 * dp);
    }

    private void updatePairButtonEnabled() {
        mPairButton.setEnabled(getService().mSelectedDevice != null);
    }

    private DeviceDiscoveryService getService() {
        return DeviceDiscoveryService.sInstance;
    }

    protected void onPairTapped(BluetoothDevice selectedDevice) {
        setResult(RESULT_OK,
                new Intent().putExtra(CompanionDeviceManager.EXTRA_DEVICE, selectedDevice));
        finish();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}