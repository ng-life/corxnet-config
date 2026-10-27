package com.corxnet.config;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;

/** Camera2 + ZXing 在本机识别 QR，不上传图像、不写入照片或识别内容。 */
public final class ScannerActivity extends Activity {
  private TextureView preview;
  private TextView status;
  private HandlerThread cameraThread;
  private Handler cameraHandler;
  private CameraDevice camera;
  private CameraCaptureSession session;
  private ImageReader images;
  private final Object imageLock = new Object();
  private Surface previewSurface;
  private volatile boolean paused = true;
  private volatile boolean complete;
  private volatile int generation;
  private boolean opening;
  private long lastDecode;
  private Size frameSize;
  private int sensorOrientation;
  private final MultiFormatReader decoder = new MultiFormatReader();

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow()
        .addFlags(
            WindowManager.LayoutParams.FLAG_SECURE
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
    hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
    hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
    hints.put(DecodeHintType.TRY_HARDER, true);
    decoder.setHints(hints);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(0xff101828);
    root.setFitsSystemWindows(true);
    TextView title = new TextView(this);
    title.setText(getIntent().getStringExtra("title"));
    title.setTextColor(0xffffffff);
    title.setTextSize(22);
    title.setPadding(24, 24, 24, 16);
    root.addView(title);
    FrameLayout cameraBox = new FrameLayout(this);
    preview = new TextureView(this);
    cameraBox.addView(preview, new FrameLayout.LayoutParams(-1, -1));
    root.addView(cameraBox, new LinearLayout.LayoutParams(-1, 0, 1));
    status = new TextView(this);
    status.setText("将二维码放在画面中央，保持清晰和光线充足");
    status.setTextColor(0xffffffff);
    status.setTextSize(15);
    status.setPadding(24, 20, 24, 12);
    root.addView(status);
    Button back = new Button(this);
    back.setText("取消扫描");
    back.setOnClickListener(v -> finish());
    root.addView(back);
    setContentView(root);
    preview.setSurfaceTextureListener(
        new TextureView.SurfaceTextureListener() {
          @Override
          public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            openCamera();
          }

          @Override
          public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            transform();
          }

          @Override
          public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            return true;
          }

          @Override
          public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
        });
  }

  @Override
  protected void onResume() {
    super.onResume();
    paused = false;
    cameraThread = new HandlerThread("corxnet-qr-camera");
    cameraThread.start();
    cameraHandler = new Handler(cameraThread.getLooper());
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.CAMERA}, 1);
    else if (preview.isAvailable()) openCamera();
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    if (request == 1 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
      openCamera();
    else status.setText(R.string.camera_permission_denied);
  }

  @Override
  protected void onPause() {
    paused = true;
    generation++;
    if (session != null) {
      session.close();
      session = null;
    }
    if (camera != null) {
      camera.close();
      camera = null;
    }
    // ImageReader.close 会使已取得 Image 的直接缓冲区失效；必须等待帧复制结束。
    synchronized (imageLock) {
      if (images != null) {
        images.close();
        images = null;
      }
    }
    if (previewSurface != null) {
      previewSurface.release();
      previewSurface = null;
    }
    opening = false;
    if (cameraThread != null) {
      cameraThread.quitSafely();
      cameraThread = null;
    }
    super.onPause();
  }

  private void fail(String message) {
    runOnUiThread(
        () -> {
          if (!paused) status.setText(message);
        });
  }

  private void openCamera() {
    if (paused
        || complete
        || opening
        || !preview.isAvailable()
        || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
      return;
    opening = true;
    final int cameraGeneration = generation;
    try {
      CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
      String cameraId = null;
      for (String id : manager.getCameraIdList()) {
        if (cameraId == null) cameraId = id;
        Integer facing =
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
          cameraId = id;
          break;
        }
      }
      if (cameraId == null) {
        opening = false;
        fail("未找到相机，可返回主页面粘贴 JSON");
        return;
      }
      CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
      Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
      sensorOrientation = orientation == null ? 0 : orientation;
      StreamConfigurationMap streams =
          characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
      if (streams == null) throw new IllegalStateException();
      Size[] sizes = streams.getOutputSizes(ImageFormat.YUV_420_888);
      if (sizes == null || sizes.length == 0) throw new IllegalStateException();
      java.util.List<Size> previewSizes =
          Arrays.asList(streams.getOutputSizes(SurfaceTexture.class));
      frameSize =
          Arrays.stream(sizes)
              .filter(previewSizes::contains)
              .filter(s -> s.getWidth() <= 1280 && s.getHeight() <= 960)
              .max(Comparator.comparingInt(s -> s.getWidth() * s.getHeight()))
              .orElse(sizes[sizes.length - 1]);
      images =
          ImageReader.newInstance(
              frameSize.getWidth(), frameSize.getHeight(), ImageFormat.YUV_420_888, 2);
      images.setOnImageAvailableListener(this::decode, cameraHandler);
      manager.openCamera(
          cameraId,
          new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice device) {
              if (paused || cameraGeneration != generation) {
                device.close();
                return;
              }
              camera = device;
              createSession();
            }

            @Override
            public void onDisconnected(CameraDevice device) {
              device.close();
              fail("相机已断开，请返回后重试");
            }

            @Override
            public void onError(CameraDevice device, int error) {
              device.close();
              fail("相机不可用，请关闭其他相机应用后重试");
            }
          },
          cameraHandler);
    } catch (Exception e) {
      opening = false;
      fail("无法打开相机，可返回主页面粘贴二维码 JSON");
    }
  }

  private void createSession() {
    final int sessionGeneration = generation;
    try {
      SurfaceTexture texture = preview.getSurfaceTexture();
      if (texture == null || paused || images == null) return;
      texture.setDefaultBufferSize(frameSize.getWidth(), frameSize.getHeight());
      previewSurface = new Surface(texture);
      Surface imageSurface = images.getSurface();
      CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
      request.addTarget(previewSurface);
      request.addTarget(imageSurface);
      request.set(
          CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
      camera.createCaptureSession(
          Arrays.asList(previewSurface, imageSurface),
          new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession configured) {
              if (paused || camera == null || sessionGeneration != generation) {
                configured.close();
                return;
              }
              session = configured;
              try {
                configured.setRepeatingRequest(request.build(), null, cameraHandler);
                runOnUiThread(() -> transform());
              } catch (Exception e) {
                fail("相机预览启动失败，请返回重试");
              }
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession configured) {
              fail("相机预览不可用，可返回粘贴 JSON");
            }
          },
          cameraHandler);
    } catch (Exception e) {
      fail("相机预览启动失败，可返回粘贴 JSON");
    }
  }

  private void transform() {
    if (frameSize == null) return;
    int rotation = getWindowManager().getDefaultDisplay().getRotation() * 90;
    int degrees = (sensorOrientation - rotation + 360) % 360;
    Matrix matrix = new Matrix();
    float cx = preview.getWidth() / 2f, cy = preview.getHeight() / 2f;
    // 缓冲区先被 TextureView 拉伸；补偿后按传感器方向旋转并居中裁切。
    boolean swap = degrees == 90 || degrees == 270;
    float bufferWidth = frameSize.getWidth(), bufferHeight = frameSize.getHeight();
    float scale =
        Math.max(
            preview.getWidth() / (swap ? bufferHeight : bufferWidth),
            preview.getHeight() / (swap ? bufferWidth : bufferHeight));
    matrix.postScale(
        scale * bufferWidth / Math.max(1, preview.getWidth()),
        scale * bufferHeight / Math.max(1, preview.getHeight()),
        cx,
        cy);
    matrix.postRotate(degrees, cx, cy);
    preview.setTransform(matrix);
  }

  private void decode(ImageReader reader) {
    final byte[] y;
    final int width, height;
    // 只在锁内访问相机原生缓冲区，复制并关闭 Image 后再执行较慢的 QR 解码。
    synchronized (imageLock) {
      Image image = null;
      try {
        if (paused || complete || reader != images) return;
        image = reader.acquireLatestImage();
        if (image == null || System.nanoTime() - lastDecode < 180_000_000L) return;
        lastDecode = System.nanoTime();
        width = image.getWidth();
        height = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        y = new byte[width * height];
        for (int row = 0; row < height; row++)
          for (int col = 0; col < width; col++) {
            y[row * width + col] =
                buffer.get(row * plane.getRowStride() + col * plane.getPixelStride());
          }
      } catch (Exception ignored) {
        return;
      } finally {
        if (image != null) image.close();
      }
    }
    if (paused || complete) return;
    Result result = tryDecode(y, width, height);
    if (result == null) {
      byte[] rotated = new byte[y.length];
      for (int row = 0; row < height; row++)
        for (int col = 0; col < width; col++) {
          rotated[col * height + height - row - 1] = y[row * width + col];
        }
      result = tryDecode(rotated, height, width);
    }
    if (result != null && !complete && !paused) {
      complete = true;
      String json = result.getText();
      runOnUiThread(
          () -> {
            if (!paused) {
              setResult(RESULT_OK, new Intent().putExtra("json", json));
              finish();
            }
          });
    }
  }

  private Result tryDecode(byte[] y, int width, int height) {
    try {
      return decoder.decodeWithState(
          new BinaryBitmap(
              new HybridBinarizer(
                  new PlanarYUVLuminanceSource(y, width, height, 0, 0, width, height, false))));
    } catch (Exception ignored) {
      return null;
    } finally {
      decoder.reset();
    }
  }
}
