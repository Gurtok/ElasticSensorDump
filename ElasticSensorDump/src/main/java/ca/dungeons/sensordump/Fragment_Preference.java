/*
 * Copyright (C) The Android Open Source Project
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
package ca.dungeons.sensordump;

import android.content.Intent;
import android.content.SharedPreferences;

import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.android.gms.common.api.CommonStatusCodes;

import ca.dungeons.sensordump.ui.camera.BarcodeMainActivity;

/**
 *
 */
public class Fragment_Preference extends PreferenceFragment {


  private static final String TAG = "Preference_Frag";

    static final int QR_REQUEST_CODE = 1232131213;
    private SharedPreferences sharedPreferences;

  /**
   *
   * @param savedInstanceState -
   */
  @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences( this.getContext() );
        addPreferencesFromResource(R.xml.preferences);
        setupQRButton();
    }

  /**
   *
   */
  private void setupQRButton(){
        Preference qrPreference = this.getPreferenceManager().findPreference( "qr_code" );
        qrPreference.setOnPreferenceClickListener( new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                Intent qr_Intent = new Intent( getContext(), BarcodeMainActivity.class );
                startActivityForResult( qr_Intent, QR_REQUEST_CODE );
                return false;
            }
        });

    }

  /**
   * @param resultCode -
   * @param data -
   */
  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent data) {

      Log.e( TAG, "Received results from QR reader." );

      if( resultCode == CommonStatusCodes.SUCCESS ){
          Log.e( TAG, "Received SUCCESS CODE" );
          if( data != null ){
              Log.e( TAG, "Intent is NOT NULL" );
              String hostString = data.getStringExtra("hostString" );
              if( ! hostString.equals("") ){
                  sharedPreferences.edit().putString( "host", hostString ).apply();
                  onCreate( this.getArguments() );
              }
          }else{
              Log.e( TAG, "Supplied intent is null !!" );
          }
      }

  }


}
