/*
 * Copyright 2020 Google Inc. All Rights Reserved.
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

package com.google.ar.core.codelab.depth;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.media.Image;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import android.util.Log;;
import android.view.View;
import android.widget.FrameLayout;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.ImageFormat;
import com.google.ar.core.Session;
import com.google.ar.core.codelab.common.helpers.CameraPermissionHelper;
import com.google.ar.core.codelab.common.helpers.DisplayRotationHelper;
import com.google.ar.core.codelab.common.helpers.FullScreenHelper;
import com.google.ar.core.codelab.common.helpers.TapHelper;
import com.google.ar.core.codelab.common.helpers.TrackingStateHelper;
import com.google.ar.core.codelab.common.rendering.BackgroundRenderer;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import com.google.mediapipe.formats.proto.LandmarkProto.Landmark;
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark;
import com.google.mediapipe.solutioncore.SolutionGlSurfaceView;
import com.google.mediapipe.solutions.hands.HandLandmark;
import com.google.mediapipe.solutions.hands.Hands;
import com.google.mediapipe.solutions.hands.HandsOptions;
import com.google.mediapipe.solutions.hands.HandsResult;

/**
 * This is a simple example that shows how to create an augmented reality (AR) application using the
 * ARCore API. The application will allow the user to tap to place a 3d model of the Android robot.
 */
public class DepthCodelabActivity extends AppCompatActivity implements GLSurfaceView.Renderer {
  private static final String TAG = DepthCodelabActivity.class.getSimpleName();

  // Rendering. The Renderers are created here, and initialized when the GL surface is created.
  private GLSurfaceView surfaceView;

  private boolean installRequested;
  private boolean isDepthSupported;

  private Session session;
//  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
//  private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);


  private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();


  // Temporary matrix allocated here to reduce number of allocations for each frame.
  private final float[] anchorMatrix = new float[16];

  private static final String SEARCHING_PLANE_MESSAGE = "Please move around slowly...";
  private static final String PLANES_FOUND_MESSAGE = "Tap to place objects.";
  private static final String DEPTH_NOT_AVAILABLE_MESSAGE = "[Depth not supported on this device]";

  // Anchors created from taps used for object placing with a given color.
  private static final float[] OBJECT_COLOR = new float[] {139.0f, 195.0f, 74.0f, 255.0f};
  private final ArrayList<Anchor> anchors = new ArrayList<>();

  private static final boolean RUN_ON_GPU = true;
  private Hands hands;
  private HandsResultImageView imageView;
  private SolutionGlSurfaceView<HandsResult> glSurfaceView;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    surfaceView = findViewById(R.id.surfaceview);
    displayRotationHelper = new DisplayRotationHelper(/*context=*/ this);


//    surfaceView.setOnTouchListener(tapHelper);

    // Set up renderer.
    surfaceView.setPreserveEGLContextOnPause(true);
    surfaceView.setEGLContextClientVersion(2);
    surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
    surfaceView.setRenderer(this);
    surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    surfaceView.setWillNotDraw(false);

    installRequested = false;

    setupStaticImageRun();
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (session == null) {
      Exception exception = null;
      String message = null;
      try {
        switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
          case INSTALL_REQUESTED:
            installRequested = true;
            return;
          case INSTALLED:
            break;
        }

        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }

        // Creates the ARCore session.
        session = new Session(/* context= */ this);
        Config config = session.getConfig();
        isDepthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC);
        if (isDepthSupported) {
          config.setDepthMode(Config.DepthMode.AUTOMATIC);
        } else {
          config.setDepthMode(Config.DepthMode.DISABLED);
        }
        session.configure(config);


      } catch (UnavailableArcoreNotInstalledException
          | UnavailableUserDeclinedInstallationException e) {
        message = "Please install ARCore";
        exception = e;
      } catch (UnavailableApkTooOldException e) {
        message = "Please update ARCore";
        exception = e;
      } catch (UnavailableSdkTooOldException e) {
        message = "Please update this app";
        exception = e;
      } catch (UnavailableDeviceNotCompatibleException e) {
        message = "This device does not support AR";
        exception = e;
      } catch (Exception e) {
        message = "Failed to create AR session";
        exception = e;
      }

      if (message != null) {
        Log.e(TAG, "Exception creating session", exception);
        return;
      }
    }

    // Note that order matters - see the note in onPause(), the reverse applies here.
    try {
      session.resume();
    } catch (CameraNotAvailableException e) {
      session = null;
      return;
    }

    surfaceView.onResume();
    displayRotationHelper.onResume();
  }

  @Override
  public void onPause() {
    super.onPause();
    if (session != null) {
      // Note that the order matters - GLSurfaceView is paused first so that it does not try
      // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
      // still call session.update() and get a SessionPausedException.
      displayRotationHelper.onPause();
      surfaceView.onPause();
      session.pause();
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }

  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

    // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
    try {
      // Create the texture and pass it to ARCore session to be filled during update().
      backgroundRenderer.createOnGlThread(/*context=*/ this);

//      virtualObject.createOnGlThread(/*context=*/ this, "models/andy.obj", "models/andy.png");
//      virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f);
    } catch (IOException e) {
      Log.e(TAG, "Failed to read an asset file", e);
    }
  }

  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
    GLES20.glViewport(0, 0, width, height);
  }

  @Override
  public void onDrawFrame(GL10 gl) {
    // Clear screen to notify driver it should not load any pixels from previous frame.
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

    if (session == null) {
      return;
    }
    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session);

    try {
      session.setCameraTextureName(backgroundRenderer.getTextureId());

      // Obtain the current frame from ARSession. When the configuration is set to
      // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
      // camera framerate.
      Frame frame = session.update();
      // If frame is ready, render camera preview image to the GL surface.
      backgroundRenderer.draw(frame);

      if (isDepthSupported) {
        try (Image image = frame.acquireCameraImage()) {
          if (image.getFormat() != ImageFormat.YUV_420_888) {
            throw new IllegalArgumentException(
                    "Expected image in YUV_420_888 format, got format " + image.getFormat());
          }

          Bitmap bitmap = yuv420ToBitmap(image,this);
          hands.send(bitmap);
        } catch (NotYetAvailableException e) {
          e.printStackTrace();
        }

        Image depthImage = frame.acquireRawDepthImage16Bits();
//        Image confidenceImage = frame.acquireRawDepthConfidenceImage();
        imageView.tipDepth = depthMillimeter(depthImage,(int)imageView.tip.x,(int)imageView.tip.y);
        Log.e(TAG, "depthImage grabbed, ------, ");
      }
    } catch (Throwable t) {
      // Avoid crashing the application due to unhandled exceptions.
      Log.e(TAG, "Exception on the OpenGL thread", t);
    }
  }

  public int depthMillimeter(Image depth, int x, int y){
    ShortBuffer depthBuffer = depth.getPlanes()[0].getBuffer().order(ByteOrder.nativeOrder()).asShortBuffer();
    return  depthBuffer.get(y * depth.getWidth() + x); // Depth image pixels are in mm.
  }

  Bitmap yuv420ToBitmap(Image image, Context context) {
    RenderScript rs = RenderScript.create(context);
    ScriptIntrinsicYuvToRGB script = ScriptIntrinsicYuvToRGB.create(
            rs, Element.U8_4(rs));

    // Refer the logic in a section below on how to convert a YUV_420_888 image
    // to single channel flat 1D array. For sake of this example I'll abstract it
    // as a method.
    byte[] yuvByteArray = YUV_420_888toNV21(image);

    Type.Builder yuvType = new Type.Builder(rs, Element.U8(rs))
            .setX(yuvByteArray.length);
    Allocation in = Allocation.createTyped(
            rs, yuvType.create(), Allocation.USAGE_SCRIPT);

    Type.Builder rgbaType = new Type.Builder(rs, Element.RGBA_8888(rs))
            .setX(image.getWidth())
            .setY(image.getHeight());
    Allocation out = Allocation.createTyped(
            rs, rgbaType.create(), Allocation.USAGE_SCRIPT);

    // The allocations above "should" be cached if you are going to perform
    // repeated conversion of YUV_420_888 to Bitmap.
    in.copyFrom(yuvByteArray);
    script.setInput(in);
    script.forEach(out);

    Bitmap bitmap = Bitmap.createBitmap(
            image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
    out.copyTo(bitmap);
    return bitmap;
  }

  private static byte[] YUV_420_888toNV21(Image image) {
    byte[] nv21;
    ByteBuffer yBuffer = image.getPlanes()[0].getBuffer();
    ByteBuffer uBuffer = image.getPlanes()[1].getBuffer();
    ByteBuffer vBuffer = image.getPlanes()[2].getBuffer();

    int ySize = yBuffer.remaining();
    int uSize = uBuffer.remaining();
    int vSize = vBuffer.remaining();

    nv21 = new byte[ySize + uSize + vSize];

    //U and V are swapped
    yBuffer.get(nv21, 0, ySize);
    vBuffer.get(nv21, ySize, vSize);
    uBuffer.get(nv21, ySize + vSize, uSize);

    return nv21;
  }

  private void setupStaticImageRun(){
    imageView = new HandsResultImageView(this);

    hands =   new Hands(
            this,
            HandsOptions.builder()
                    .setStaticImageMode(true)
                    .setMaxNumHands(2)
                    .setRunOnGpu(RUN_ON_GPU)
                    .build());

    // Connects MediaPipe Hands solution to the user-defined HandsResultImageView.
    hands.setResultListener(
            handsResult -> {
              logWristLandmark(handsResult, /*showPixelValues=*/ true);
              imageView.setHandsResult(handsResult);
              runOnUiThread(() -> imageView.update());
            });
    hands.setErrorListener((message, e) -> Log.e(TAG, "MediaPipe Hands error:" + message));

    // Updates the preview layout.
    FrameLayout frameLayout = findViewById(R.id.preview_display_layout);
    frameLayout.removeAllViewsInLayout();
    imageView.setImageDrawable(null);
    frameLayout.addView(imageView);
    imageView.setVisibility(View.VISIBLE);
  }



  private PointF indexFingerTip(HandsResult result){
    NormalizedLandmark tip = result.multiHandLandmarks().get(0).getLandmarkList().get(HandLandmark.INDEX_FINGER_TIP);
    int width = result.inputBitmap().getWidth();
    int height = result.inputBitmap().getHeight();
    return new PointF(tip.getX()*width,tip.getY()*height);
  }

//  PointF tip = new PointF();

  private void logWristLandmark(HandsResult result, boolean showPixelValues) {
    if (result.multiHandLandmarks().isEmpty()) {
      return;
    }
    imageView.tip = indexFingerTip(result);
    NormalizedLandmark wristLandmark =
            result.multiHandLandmarks().get(0).getLandmarkList().get(HandLandmark.WRIST);
    // For Bitmaps, show the pixel values. For texture inputs, show the normalized coordinates.
    if (showPixelValues) {
      int width = result.inputBitmap().getWidth();
      int height = result.inputBitmap().getHeight();
      Log.i(
              TAG,
              String.format(
                      "MediaPipe Hand wrist coordinates (pixel values): x=%f, y=%f",
                      wristLandmark.getX() * width, wristLandmark.getY() * height));
    } else {
      Log.i(
              TAG,
              String.format(
                      "MediaPipe Hand wrist normalized coordinates (value range: [0, 1]): x=%f, y=%f",
                      wristLandmark.getX(), wristLandmark.getY()));
    }
    if (result.multiHandWorldLandmarks().isEmpty()) {
      return;
    }
    Landmark wristWorldLandmark =
            result.multiHandWorldLandmarks().get(0).getLandmarkList().get(HandLandmark.WRIST);
    Log.i(
            TAG,
            String.format(
                    "MediaPipe Hand wrist world coordinates (in meters with the origin at the hand's"
                            + " approximate geometric center): x=%f m, y=%f m, z=%f m",
                    wristWorldLandmark.getX(), wristWorldLandmark.getY(), wristWorldLandmark.getZ()));
  }

}
