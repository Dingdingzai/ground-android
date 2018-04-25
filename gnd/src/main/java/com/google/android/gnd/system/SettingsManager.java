/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.gnd.system;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.support.annotation.NonNull;
import android.util.Log;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsStatusCodes;
import io.reactivex.Completable;
import io.reactivex.CompletableEmitter;
import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class SettingsManager {
  private static final String TAG = SettingsManager.class.getSimpleName();
  private static final int CHECK_SETTINGS_REQUEST_CODE = SettingsManager.class.hashCode() & 0xffff;

  private final Context context;
  private final Subject<SettingsChangeRequest> settingsChangeRequestSubject;
  private final Subject<Boolean> settingsChangeResultSubject;

  @Inject
  public SettingsManager(Application app) {
    this.context = app.getApplicationContext();
    this.settingsChangeRequestSubject = PublishSubject.create();
    this.settingsChangeResultSubject = PublishSubject.create();
  }

  public Observable<SettingsChangeRequest> settingsChangeRequests() {
    return settingsChangeRequestSubject;
  }

  public Completable enableLocationSettings(LocationRequest locationRequest) {
    return Completable.create(source -> {
      Log.d(TAG, "Checking location settings");
      LocationSettingsRequest settingsRequest = new LocationSettingsRequest.Builder()
          .addLocationRequest(locationRequest).build();
      LocationServices.getSettingsClient(context)
          .checkLocationSettings(settingsRequest)
          .addOnSuccessListener(v -> onCheckLocationSettingsSuccess(source))
          .addOnFailureListener(e -> onCheckLocationSettingsFailure(e, source));
    });
  }

  private void onCheckLocationSettingsSuccess(CompletableEmitter src) {
    Log.d(TAG, "Location settings already enabled");
    src.onComplete();
  }

  private void onCheckLocationSettingsFailure(Exception e, CompletableEmitter src) {
    if ((e instanceof ResolvableApiException)
        && isResolutionRequired((ResolvableApiException) e)) {
      Log.d(TAG, "Prompting user to enable location settings");
      // Attach settings change result stream to Completable returned by checkLocationSettings().
      settingsChangeResultSubject.subscribe(ok -> onSettingsChangeResult(ok, src));
      // Prompt user to enable Location in Settings.
      settingsChangeRequestSubject
          .onNext(new SettingsChangeRequest((ResolvableApiException) e));
    } else {
      Log.d(TAG, "Unable to prompt user to enable location settings");
      src.onError(e);
    }
  }

  @NonNull
  private void onSettingsChangeResult(boolean ok, CompletableEmitter src) {
    Log.d(TAG, "Received settings change result: " + ok);
    if (ok) {
      src.onComplete();
    } else {
      src.onError(new SettingsChangeRequestCanceled());
    }
  }

  private boolean isResolutionRequired(ResolvableApiException e) {
    return e.getStatusCode() == LocationSettingsStatusCodes.RESOLUTION_REQUIRED;
  }

  public void onActivityResult(int requestCode, int resultCode) {
    if (requestCode != CHECK_SETTINGS_REQUEST_CODE) {
      return;
    }
    Log.d(TAG, "Location settings resultCode received: " + resultCode);
    switch (resultCode) {
      case Activity.RESULT_OK:
        settingsChangeResultSubject.onNext(true);
        break;
      case Activity.RESULT_CANCELED:
        settingsChangeResultSubject.onNext(false);
        break;
      default:
        break;
    }
  }

  public static class SettingsChangeRequest {
    private ResolvableApiException exception;
    private int requestCode;

    private SettingsChangeRequest(ResolvableApiException exception) {
      this.exception = exception;
      this.requestCode = CHECK_SETTINGS_REQUEST_CODE;
    }

    public ResolvableApiException getException() {
      return exception;
    }

    public int getRequestCode() {
      return requestCode;
    }
  }

  public static class SettingsChangeRequestCanceled extends Exception {
  }
}
