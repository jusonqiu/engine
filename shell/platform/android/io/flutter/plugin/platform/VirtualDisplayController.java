// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.platform;

import static android.view.View.OnFocusChangeListener;

import android.annotation.TargetApi;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewTreeObserver;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import io.flutter.Log;
import io.flutter.view.TextureRegistry;
import java.util.Locale;

@TargetApi(20)
class VirtualDisplayController {
  private static String TAG = "VirtualDisplayController";

  public static VirtualDisplayController create(
      Context context,
      AccessibilityEventsDelegate accessibilityEventsDelegate,
      PlatformView view,
      TextureRegistry.SurfaceTextureEntry textureEntry,
      int width,
      int height,
      int viewId,
      Object createParams,
      OnFocusChangeListener focusChangeListener) {

    int selectedWidth = width;
    int selectedHeight = height;

    DisplayMetrics metrics = context.getResources().getDisplayMetrics();
    if (selectedWidth == 0 || selectedHeight == 0) {
      return null;
    }
    // Prevent https://github.com/flutter/flutter/issues/2897.
    if (selectedWidth > metrics.widthPixels || selectedHeight > metrics.heightPixels) {
      float aspectRatio = (float) selectedWidth / (float) selectedHeight;
      int maybeWidth = (int) (metrics.heightPixels * aspectRatio);
      int maybeHeight = (int) (metrics.widthPixels / aspectRatio);

      if (maybeHeight <= metrics.heightPixels) {
        selectedWidth = metrics.widthPixels;
        selectedHeight = maybeHeight;
      } else if (maybeWidth <= metrics.widthPixels) {
        selectedHeight = metrics.heightPixels;
        selectedWidth = maybeWidth;
      } else {
        return null;
      }

      String message =
          String.format(
              Locale.US,
              "Resizing virtual display of size: [%d, %d] to size [%d, %d] "
                  + "since it's larger than the device display size [%d, %d].",
              width,
              height,
              selectedWidth,
              selectedHeight,
              metrics.widthPixels,
              metrics.heightPixels);
      Log.w(TAG, message);
    }

    textureEntry.surfaceTexture().setDefaultBufferSize(selectedWidth, selectedHeight);
    Surface surface = new Surface(textureEntry.surfaceTexture());
    DisplayManager displayManager =
        (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);

    int densityDpi = context.getResources().getDisplayMetrics().densityDpi;
    VirtualDisplay virtualDisplay =
        displayManager.createVirtualDisplay(
            "flutter-vd", selectedWidth, selectedHeight, densityDpi, surface, 0);

    if (virtualDisplay == null) {
      return null;
    }
    VirtualDisplayController controller =
        new VirtualDisplayController(
            context,
            accessibilityEventsDelegate,
            virtualDisplay,
            view,
            surface,
            textureEntry,
            focusChangeListener,
            viewId,
            createParams);
    controller.bufferWidth = selectedWidth;
    controller.bufferHeight = selectedHeight;
    return controller;
  }

  @VisibleForTesting SingleViewPresentation presentation;

  private final Context context;
  private final AccessibilityEventsDelegate accessibilityEventsDelegate;
  private final int densityDpi;
  private final TextureRegistry.SurfaceTextureEntry textureEntry;
  private final OnFocusChangeListener focusChangeListener;
  private final Surface surface;

  private VirtualDisplay virtualDisplay;
  private int bufferWidth;
  private int bufferHeight;

  private VirtualDisplayController(
      Context context,
      AccessibilityEventsDelegate accessibilityEventsDelegate,
      VirtualDisplay virtualDisplay,
      PlatformView view,
      Surface surface,
      TextureRegistry.SurfaceTextureEntry textureEntry,
      OnFocusChangeListener focusChangeListener,
      int viewId,
      Object createParams) {
    this.context = context;
    this.accessibilityEventsDelegate = accessibilityEventsDelegate;
    this.textureEntry = textureEntry;
    this.focusChangeListener = focusChangeListener;
    this.surface = surface;
    this.virtualDisplay = virtualDisplay;
    densityDpi = context.getResources().getDisplayMetrics().densityDpi;
    presentation =
        new SingleViewPresentation(
            context,
            this.virtualDisplay.getDisplay(),
            view,
            accessibilityEventsDelegate,
            viewId,
            createParams,
            focusChangeListener);
    presentation.show();
  }

  public int getBufferWidth() {
    return bufferWidth;
  }

  public int getBufferHeight() {
    return bufferHeight;
  }

  public void resize(final int width, final int height, final Runnable onNewSizeFrameAvailable) {
    boolean isFocused = getView().isFocused();
    final SingleViewPresentation.PresentationState presentationState = presentation.detachState();
    // We detach the surface to prevent it being destroyed when releasing the vd.
    //
    // setSurface is only available starting API 20. We could support API 19 by re-creating a new
    // SurfaceTexture here. This will require refactoring the TextureRegistry to allow recycling
    // texture
    // entry IDs.
    virtualDisplay.setSurface(null);
    virtualDisplay.release();

    bufferWidth = width;
    bufferHeight = height;
    textureEntry.surfaceTexture().setDefaultBufferSize(width, height);
    DisplayManager displayManager =
        (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
    virtualDisplay =
        displayManager.createVirtualDisplay("flutter-vd", width, height, densityDpi, surface, 0);

    final View embeddedView = getView();
    // There's a bug in Android version older than O where view tree observer onDrawListeners don't
    // get properly
    // merged when attaching to window, as a workaround we register the on draw listener after the
    // view is attached.
    embeddedView.addOnAttachStateChangeListener(
        new View.OnAttachStateChangeListener() {
          @Override
          public void onViewAttachedToWindow(View v) {
            OneTimeOnDrawListener.schedule(
                embeddedView,
                new Runnable() {
                  @Override
                  public void run() {
                    // We need some delay here until the frame propagates through the vd surface to
                    // to the texture,
                    // 128ms was picked pretty arbitrarily based on trial and error.
                    // As long as we invoke the runnable after a new frame is available we avoid the
                    // scaling jank
                    // described in: https://github.com/flutter/flutter/issues/19572
                    // We should ideally run onNewSizeFrameAvailable ASAP to make the embedded view
                    // more responsive
                    // following a resize.
                    embeddedView.postDelayed(onNewSizeFrameAvailable, 128);
                  }
                });
            embeddedView.removeOnAttachStateChangeListener(this);
          }

          @Override
          public void onViewDetachedFromWindow(View v) {}
        });

    // Create a new SingleViewPresentation and show() it before we cancel() the existing
    // presentation. Calling show() and cancel() in this order fixes
    // https://github.com/flutter/flutter/issues/26345 and maintains seamless transition
    // of the contents of the presentation.
    SingleViewPresentation newPresentation =
        new SingleViewPresentation(
            context,
            virtualDisplay.getDisplay(),
            accessibilityEventsDelegate,
            presentationState,
            focusChangeListener,
            isFocused);
    newPresentation.show();
    presentation.cancel();
    presentation = newPresentation;
  }

  public void dispose() {
    // Fix rare crash on HuaWei device described in: https://github.com/flutter/engine/pull/9192
    presentation.cancel();
    presentation.detachState();
    virtualDisplay.release();
    textureEntry.release();
  }

  /** See {@link PlatformView#onFlutterViewAttached(View)} */
  /*package*/ void onFlutterViewAttached(@NonNull View flutterView) {
    if (presentation == null || presentation.getView() == null) {
      return;
    }
    presentation.getView().onFlutterViewAttached(flutterView);
  }

  /** See {@link PlatformView#onFlutterViewDetached()} */
  /*package*/ void onFlutterViewDetached() {
    if (presentation == null || presentation.getView() == null) {
      return;
    }
    presentation.getView().onFlutterViewDetached();
  }

  /*package*/ void onInputConnectionLocked() {
    if (presentation == null || presentation.getView() == null) {
      return;
    }
    presentation.getView().onInputConnectionLocked();
  }

  /*package*/ void onInputConnectionUnlocked() {
    if (presentation == null || presentation.getView() == null) {
      return;
    }
    presentation.getView().onInputConnectionUnlocked();
  }

  public View getView() {
    if (presentation == null) return null;
    PlatformView platformView = presentation.getView();
    return platformView.getView();
  }

  /** Dispatches a motion event to the presentation for this controller. */
  public void dispatchTouchEvent(MotionEvent event) {
    if (presentation == null) return;
    presentation.dispatchTouchEvent(event);
  }

  static class OneTimeOnDrawListener implements ViewTreeObserver.OnDrawListener {
    static void schedule(View view, Runnable runnable) {
      OneTimeOnDrawListener listener = new OneTimeOnDrawListener(view, runnable);
      view.getViewTreeObserver().addOnDrawListener(listener);
    }

    final View mView;
    Runnable mOnDrawRunnable;

    OneTimeOnDrawListener(View view, Runnable onDrawRunnable) {
      this.mView = view;
      this.mOnDrawRunnable = onDrawRunnable;
    }

    @Override
    public void onDraw() {
      if (mOnDrawRunnable == null) {
        return;
      }
      mOnDrawRunnable.run();
      mOnDrawRunnable = null;
      mView.post(
          new Runnable() {
            @Override
            public void run() {
              mView.getViewTreeObserver().removeOnDrawListener(OneTimeOnDrawListener.this);
            }
          });
    }
  }
}
