package io.flutter.plugins.camera;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import androidx.annotation.NonNull;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugins.camera.MethodCallHandlerImpl.BracketingMode;
import io.flutter.view.TextureRegistry.SurfaceTextureEntry;

import static android.view.OrientationEventListener.ORIENTATION_UNKNOWN;
import static io.flutter.plugins.camera.CameraUtils.computeBestPreviewSize;

public class Camera {
    private final SurfaceTextureEntry flutterTexture;
    private final CameraManager cameraManager;
    private final OrientationEventListener orientationEventListener;
    private final boolean isFrontFacing;
    private final int sensorOrientation;
    private final String cameraName;
    private final Size captureSize;
    private final Size previewSize;
    private final boolean enableAudio;
    private final CameraPropertiesMessenger propertiesMessenger;

    private CameraDevice cameraDevice;
    private CameraCharacteristics cameraCharacteristics;
    private CameraCaptureSession cameraCaptureSession;
    private ImageReader pictureImageJpegReader;
    private ImageReader pictureImageRawReader;
    private ImageReader imageStreamReader;
    private ImageReader currentReader;
    private DartMessenger dartMessenger;
    private CaptureRequest.Builder captureRequestBuilder;
    private MediaRecorder mediaRecorder;
    private boolean recordingVideo;
    private CamcorderProfile recordingProfile;
    private int currentOrientation = ORIENTATION_UNKNOWN;
    private Integer lastIso;
    private Long lastExposureTime;
    private boolean aeLock = false;
    private CaptureResult lastCaptureResult;
    private long minFrameDuration;

    public void aeLock(boolean enabled, Result result) throws CameraAccessException {
        if (aeLock == enabled) {
            return;
        }

        aeLock = enabled;
        if (currentReader == pictureImageJpegReader) {
            startJpegPreview();
        } else if (currentReader == pictureImageRawReader) {
            startRawPreview();
        } else if (currentReader == imageStreamReader) {
            throw new RuntimeException("Currently not supporting ae lock with video streams");
        }

        result.success(aeLock);
    }

    // Mirrors camera.dart
    public enum ResolutionPreset {
        low,
        medium,
        high,
        veryHigh,
        ultraHigh,
        max,
    }

    public Camera(
            final Activity activity,
            final SurfaceTextureEntry flutterTexture,
            final DartMessenger dartMessenger,
            final CameraPropertiesMessenger propertiesMessenger,
            final String cameraName,
            final String resolutionPreset,
            final boolean enableAudio)
            throws CameraAccessException {
        if (activity == null) {
            throw new IllegalStateException("No activity available!");
        }

        this.propertiesMessenger = propertiesMessenger;
        this.cameraName = cameraName;
        this.enableAudio = enableAudio;
        this.flutterTexture = flutterTexture;
        this.dartMessenger = dartMessenger;
        this.cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        orientationEventListener =
                new OrientationEventListener(activity.getApplicationContext()) {
                    @Override
                    public void onOrientationChanged(int i) {
                        if (i == ORIENTATION_UNKNOWN) {
                            return;
                        }
                        // Convert the raw deg angle to the nearest multiple of 90.
                        currentOrientation = (int) Math.round(i / 90.0) * 90;
                    }
                };
        orientationEventListener.enable();

        CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraName);
        StreamConfigurationMap streamConfigurationMap =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        //noinspection ConstantConditions
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        //noinspection ConstantConditions
        isFrontFacing =
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT;
        ResolutionPreset preset = ResolutionPreset.valueOf(resolutionPreset);
        recordingProfile =
                CameraUtils.getBestAvailableCamcorderProfileForResolutionPreset(cameraName, preset);
        captureSize = new Size(recordingProfile.videoFrameWidth, recordingProfile.videoFrameHeight);
        previewSize = computeBestPreviewSize(cameraName, preset);
    }

    private void prepareMediaRecorder(String outputFilePath) throws IOException {
        if (mediaRecorder != null) {
            mediaRecorder.release();
        }
        mediaRecorder = new MediaRecorder();

        // There's a specific order that mediaRecorder expects. Do not change the order
        // of these function calls.
        if (enableAudio) mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(recordingProfile.fileFormat);
        if (enableAudio) mediaRecorder.setAudioEncoder(recordingProfile.audioCodec);
        mediaRecorder.setVideoEncoder(recordingProfile.videoCodec);
        mediaRecorder.setVideoEncodingBitRate(recordingProfile.videoBitRate);
        if (enableAudio) mediaRecorder.setAudioSamplingRate(recordingProfile.audioSampleRate);
        mediaRecorder.setVideoFrameRate(recordingProfile.videoFrameRate);
        mediaRecorder.setVideoSize(recordingProfile.videoFrameWidth, recordingProfile.videoFrameHeight);
        mediaRecorder.setOutputFile(outputFilePath);
        mediaRecorder.setOrientationHint(getMediaOrientation());

        mediaRecorder.prepare();
    }

    @SuppressLint("MissingPermission")
    public void open(@NonNull final Result result) throws CameraAccessException {
        pictureImageJpegReader =
                ImageReader.newInstance(
                        captureSize.getWidth(), captureSize.getHeight(), ImageFormat.JPEG, 3);

        cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraName);
        StreamConfigurationMap configurationMap = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] rawSize = null;
        if (configurationMap != null) {
            rawSize = configurationMap.getOutputSizes(ImageFormat.RAW_SENSOR);
            if (rawSize != null && rawSize.length > 0) {
                minFrameDuration = configurationMap.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, rawSize[0]);
                Log.d("CAMERA", "OutputSize: " + rawSize[0] + ", minSpeed: " + minFrameDuration);

                pictureImageRawReader =
                        ImageReader.newInstance(
                                rawSize[0].getWidth(), rawSize[0].getHeight(), ImageFormat.RAW_SENSOR, 3);
            }
        }

        // Used to steam image byte data to dart side.
        imageStreamReader =
                ImageReader.newInstance(
                        previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 2);

        cameraManager.openCamera(
                cameraName,
                new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@NonNull CameraDevice device) {
                        cameraDevice = device;
                        try {
                            startJpegPreview();
                        } catch (CameraAccessException e) {
                            result.error("CameraAccess", e.getMessage(), null);
                            close();
                            return;
                        }
                        Map<String, Object> reply = new HashMap<>();
                        reply.put("textureId", flutterTexture.id());
                        reply.put("previewWidth", previewSize.getWidth());
                        reply.put("previewHeight", previewSize.getHeight());
                        result.success(reply);
                    }

                    @Override
                    public void onClosed(@NonNull CameraDevice camera) {
                        dartMessenger.sendCameraClosingEvent();
                        super.onClosed(camera);
                    }

                    @Override
                    public void onDisconnected(@NonNull CameraDevice cameraDevice) {
                        close();
                        dartMessenger.send(DartMessenger.EventType.ERROR, "The camera was disconnected.");
                    }

                    @Override
                    public void onError(@NonNull CameraDevice cameraDevice, int errorCode) {
                        close();
                        String errorDescription;
                        switch (errorCode) {
                            case ERROR_CAMERA_IN_USE:
                                errorDescription = "The camera device is in use already.";
                                break;
                            case ERROR_MAX_CAMERAS_IN_USE:
                                errorDescription = "Max cameras in use";
                                break;
                            case ERROR_CAMERA_DISABLED:
                                errorDescription = "The camera device could not be opened due to a device policy.";
                                break;
                            case ERROR_CAMERA_DEVICE:
                                errorDescription = "The camera device has encountered a fatal error";
                                break;
                            case ERROR_CAMERA_SERVICE:
                                errorDescription = "The camera service has encountered a fatal error.";
                                break;
                            default:
                                errorDescription = "Unknown camera error";
                        }
                        dartMessenger.send(DartMessenger.EventType.ERROR, errorDescription);
                    }
                },
                null);
    }

    private String writeToFile(Image image, String basePath) throws IOException {
        if (image.getFormat() == ImageFormat.RAW_SENSOR) {
            File file = new File(basePath + ".dng");
            DngCreator dngCreator = new DngCreator(cameraCharacteristics, lastCaptureResult);
            try (FileOutputStream stream = new FileOutputStream(file)) {
                dngCreator.writeImage(stream, image);
                return file.getPath();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else if (image.getFormat() == ImageFormat.JPEG) {
            File file = new File(basePath + ".jpg");

            try (FileOutputStream outputStream = new FileOutputStream(file)) {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                while (0 < buffer.remaining()) {
                    outputStream.getChannel().write(buffer);
                }
                return file.getPath();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    SurfaceTextureEntry getFlutterTexture() {
        return flutterTexture;
    }

    public void takePicture(String filePath, @NonNull final Result result) {
        final File file = new File(filePath);

        if (file.exists()) {
            result.error(
                    "fileExists", "File at path '" + filePath + "' already exists. Cannot overwrite.", null);
            return;
        }

        currentReader.setOnImageAvailableListener(
                reader -> {
                    try (Image image = reader.acquireLatestImage()) {
                        String path = writeToFile(image, filePath);
                        result.success(path);
                    } catch (IOException e) {
                        result.error("IOError", "Failed saving image", null);
                    }
                },
                null);

        try {
            final CaptureRequest.Builder captureBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(currentReader.getSurface());
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getMediaOrientation());
            captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 100);
            if (aeLock && lastIso != null && lastExposureTime != null) {
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, lastExposureTime);
                captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, lastIso);
            } else {
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
            }

            cameraCaptureSession.capture(
                    captureBuilder.build(),
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureFailed(
                                @NonNull CameraCaptureSession session,
                                @NonNull CaptureRequest request,
                                @NonNull CaptureFailure failure) {
                            String reason;
                            switch (failure.getReason()) {
                                case CaptureFailure.REASON_ERROR:
                                    reason = "An error happened in the framework";
                                    break;
                                case CaptureFailure.REASON_FLUSHED:
                                    reason = "The capture has failed due to an abortCaptures() call";
                                    break;
                                default:
                                    reason = "Unknown reason";
                            }
                            result.error("captureFailure", reason, null);
                        }
                    },
                    null);
        } catch (CameraAccessException e) {
            result.error("cameraAccess", e.getMessage(), null);
        }
    }

    public void takeBracketingPictures(String basePath, BracketingMode bracketingMode, @NonNull final Result result) {
        try {
            List<CaptureRequest> captureList = null;

            if (bracketingMode == BracketingMode.autoExposureCompensation) {
                captureList = createAeCompensationReaderSession(basePath, result);
            } else if (bracketingMode == BracketingMode.fixedIsoTimeCompensation || aeLock) {
                captureList = createFixedIsoBracketingReaderSession(basePath, result);
            }

            if (captureList != null) {
                cameraCaptureSession.captureBurst(
                        captureList,
                        new CameraCaptureSession.CaptureCallback() {
                            @Override
                            public void onCaptureFailed(
                                    @NonNull CameraCaptureSession session,
                                    @NonNull CaptureRequest request,
                                    @NonNull CaptureFailure failure) {

                                String reason;
                                switch (failure.getReason()) {
                                    case CaptureFailure.REASON_ERROR:
                                        reason = "An error happened in the framework";
                                        break;
                                    case CaptureFailure.REASON_FLUSHED:
                                        reason = "The capture has failed due to an abortCaptures() call";
                                        break;
                                    default:
                                        reason = "Unknown reason";
                                }
                                result.error("captureFailure", reason, null);
                            }
                        },
                        null);
            }
        } catch (CameraAccessException e) {
            result.error("cameraAccess", e.getMessage(), null);
        }

    }

    private List<CaptureRequest> createFixedIsoBracketingReaderSession(String basePath, @NonNull Result result) throws CameraAccessException {
        Range<Long> exposureTimeRange = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        List<CaptureRequest> captureList = new ArrayList<CaptureRequest>();
        int numberOfBursts = 3;
        long time = lastExposureTime;
        int iso = lastIso;
        for (int n = -1; n < numberOfBursts - 1; n++) {
            long exposureTime = exposureTimeRange.clamp((long) (time * Math.pow(2, n * 1.3)));
            Log.d("CAMERA", exposureTimeRange + " possible, selected " + exposureTime + " and iso: " + iso + ", current time + " + time);
            CaptureRequest.Builder captureBuilder = createManualCompensationBuilder(iso, exposureTime);
            captureList.add(captureBuilder.build());
        }

        final AtomicInteger index = new AtomicInteger(0);
        final AtomicLong firstShot = new AtomicLong(0);
        final List<String> paths = new ArrayList<>();

        currentReader.setOnImageAvailableListener(
                reader -> {
                    try (Image image = reader.acquireNextImage()) {

                        if (firstShot.get() == 0) {
                            firstShot.set(System.currentTimeMillis());
                        }

                        int i = index.getAndIncrement();
                        Log.d("CAMERA", System.currentTimeMillis() - firstShot.get() + "msec for " + i + " before writing");
                        String path = writeToFile(image, basePath + "_" + (i + 1));
                        Log.d("CAMERA", "Wrote to " + path);
                        paths.add(path);

                        Log.d("CAMERA", System.currentTimeMillis() - firstShot.get() + "msec for " + i + " after writing");
                        if (i == captureList.size() - 1) {
                            result.success(paths);
                        }

                    } catch (Exception e) {
                        result.error("IOError", "Failed saving image", null);
                    }
                },
                null);

        return captureList;
    }


    private List<CaptureRequest> createAeCompensationReaderSession(String basePath, @NonNull Result result) throws CameraAccessException {
        List<CaptureRequest> captureList = new ArrayList<CaptureRequest>();
        List<Integer> aeCompensations = createAeCompensations();
        // first frame will be discarded
        captureList.add(createAECompansationBuilder(0).build());
        for (int aeCompensation : aeCompensations) {
            CaptureRequest.Builder captureBuilder = createAECompansationBuilder(aeCompensation);
            captureList.add(captureBuilder.build());
        }

        final AtomicInteger index = new AtomicInteger(0);
        final AtomicLong firstShot = new AtomicLong(0);
        final List<String> paths = new ArrayList<>();

        currentReader.setOnImageAvailableListener(
                reader -> {
                    try (Image image = reader.acquireNextImage()) {

                        if (firstShot.get() == 0) {
                            firstShot.set(System.currentTimeMillis());
                        }

                        int i = index.getAndIncrement();
                        Log.d("CAMERA", System.currentTimeMillis() - firstShot.get() + "msec for " + i + " before writing");

                        if (i == 0) {
                            Log.d("CAMERA", "Discard the first frame to settle the AE compensation");
                            return;
                        }

                        String path = writeToFile(image, basePath + "_" + i);
                        Log.d("CAMERA", "Wrote to" + path);
                        paths.add(path);

                        Log.d("CAMERA", System.currentTimeMillis() - firstShot.get() + "msec for " + i + " after writing");
                        if (i == captureList.size() - 1) {
                            result.success(paths);
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                        result.error("IOError", "Failed saving image", null);
                    }
                },
                null);

        return captureList;
    }

    private List<Integer> createAeCompensations() throws CameraAccessException {
        Range<Integer> range = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        final double step = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP).doubleValue();


        List<Integer> aeCompensations = new ArrayList();
        if (range.getLower() == 0 || range.getUpper() == 0) {
            aeCompensations.add(0);
        } else {
            aeCompensations.add((int) (range.getLower() * step));
            aeCompensations.add(0);
            aeCompensations.add((int) (range.getUpper() * step));
        }
        return aeCompensations;
    }

    private CaptureRequest.Builder createAECompansationBuilder(int aeCompensation) throws CameraAccessException {
        CaptureRequest.Builder captureBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        captureBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, minFrameDuration);
        captureBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
        captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 100);
        captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getMediaOrientation());
        captureBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeCompensation);
        captureBuilder.addTarget(currentReader.getSurface());
        return captureBuilder;
    }

    private CaptureRequest.Builder createManualCompensationBuilder(int iso, long exposureTime) throws CameraAccessException {
        CaptureRequest.Builder captureBuilder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        captureBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION, minFrameDuration);
        captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
        captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime);
        captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
        captureBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
        captureBuilder.set(CaptureRequest.JPEG_QUALITY, (byte) 100);
        captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getMediaOrientation());
        captureBuilder.addTarget(currentReader.getSurface());
        return captureBuilder;
    }


    private void createCaptureSession(int templateType, Surface... surfaces)
            throws CameraAccessException {
        createCaptureSession(templateType, null, surfaces);
    }

    private void createCaptureSession(
            int templateType, Runnable onSuccessCallback, Surface... surfaces)
            throws CameraAccessException {
        // Close any existing capture session.
        closeCaptureSession();

        // Create a new capture builder.
        captureRequestBuilder = cameraDevice.createCaptureRequest(templateType);

        // Build Flutter surface to render to
        SurfaceTexture surfaceTexture = flutterTexture.surfaceTexture();
        surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        Surface flutterSurface = new Surface(surfaceTexture);
        if (aeLock && lastIso != null && lastExposureTime != null) {
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
            captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, lastExposureTime);
            captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, lastIso);
        } else {
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
        }
        captureRequestBuilder.addTarget(flutterSurface);

        List<Surface> remainingSurfaces = Arrays.asList(surfaces);
        if (templateType != CameraDevice.TEMPLATE_PREVIEW) {
            // If it is not preview mode, add all surfaces as targets.
            for (Surface surface : remainingSurfaces) {
                captureRequestBuilder.addTarget(surface);
            }
        }

        // Prepare the callback
        CameraCaptureSession.StateCallback callback =
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        try {
                            if (cameraDevice == null) {
                                dartMessenger.send(
                                        DartMessenger.EventType.ERROR, "The camera was closed during configuration.");
                                return;
                            }
                            cameraCaptureSession = session;
                            captureRequestBuilder.set(
                                    CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                            cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                                @Override
                                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                                    lastCaptureResult = result;
                                    lastExposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                                    lastIso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                                    updateProperties(result);
                                }
                            }, null);
                            if (onSuccessCallback != null) {
                                onSuccessCallback.run();
                            }
                        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
                            dartMessenger.send(DartMessenger.EventType.ERROR, e.getMessage());
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                        dartMessenger.send(
                                DartMessenger.EventType.ERROR, "Failed to configure camera session.");
                    }
                };

        // Collect all surfaces we want to render to.
        List<Surface> surfaceList = new ArrayList<>();
        surfaceList.add(flutterSurface);
        surfaceList.addAll(remainingSurfaces);
        // Start the session
        cameraDevice.createCaptureSession(surfaceList, callback, null);
    }

    private void updateProperties(TotalCaptureResult result) {
        if (!aeLock) {
            lastExposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            lastIso = result.get(CaptureResult.SENSOR_SENSITIVITY);
            if (lastExposureTime != null && lastIso != null) {
                propertiesMessenger.send(lastIso, lastExposureTime);
            }
        }
    }

    public void startVideoRecording(String filePath, Result result) {
        if (new File(filePath).exists()) {
            result.error("fileExists", "File at path '" + filePath + "' already exists.", null);
            return;
        }
        try {
            prepareMediaRecorder(filePath);
            recordingVideo = true;
            createCaptureSession(
                    CameraDevice.TEMPLATE_RECORD, () -> mediaRecorder.start(), mediaRecorder.getSurface());
            result.success(null);
        } catch (CameraAccessException | IOException e) {
            result.error("videoRecordingFailed", e.getMessage(), null);
        }
    }

    public void stopVideoRecording(@NonNull final Result result) {
        if (!recordingVideo) {
            result.success(null);
            return;
        }

        try {
            recordingVideo = false;
            mediaRecorder.stop();
            mediaRecorder.reset();
            startJpegPreview();
            result.success(null);
        } catch (CameraAccessException | IllegalStateException e) {
            result.error("videoRecordingFailed", e.getMessage(), null);
        }
    }

    public void pauseVideoRecording(@NonNull final Result result) {
        if (!recordingVideo) {
            result.success(null);
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder.pause();
            } else {
                result.error("videoRecordingFailed", "pauseVideoRecording requires Android API +24.", null);
                return;
            }
        } catch (IllegalStateException e) {
            result.error("videoRecordingFailed", e.getMessage(), null);
            return;
        }

        result.success(null);
    }

    public void resumeVideoRecording(@NonNull final Result result) {
        if (!recordingVideo) {
            result.success(null);
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder.resume();
            } else {
                result.error(
                        "videoRecordingFailed", "resumeVideoRecording requires Android API +24.", null);
                return;
            }
        } catch (IllegalStateException e) {
            result.error("videoRecordingFailed", e.getMessage(), null);
            return;
        }

        result.success(null);
    }

    public void startRawPreview() throws CameraAccessException {
        currentReader = pictureImageRawReader;
        createCaptureSession(CameraDevice.TEMPLATE_PREVIEW, pictureImageRawReader.getSurface());
    }

    public void startJpegPreview() throws CameraAccessException {
        currentReader = pictureImageJpegReader;
        createCaptureSession(CameraDevice.TEMPLATE_PREVIEW, pictureImageJpegReader.getSurface());
    }

    public void startPreviewWithImageStream(EventChannel imageStreamChannel)
            throws CameraAccessException {
        createCaptureSession(CameraDevice.TEMPLATE_RECORD, imageStreamReader.getSurface());

        imageStreamChannel.setStreamHandler(
                new EventChannel.StreamHandler() {
                    @Override
                    public void onListen(Object o, EventChannel.EventSink imageStreamSink) {
                        setImageStreamImageAvailableListener(imageStreamSink);
                    }

                    @Override
                    public void onCancel(Object o) {
                        imageStreamReader.setOnImageAvailableListener(null, null);
                    }
                });
    }

    private void setImageStreamImageAvailableListener(final EventChannel.EventSink imageStreamSink) {
        imageStreamReader.setOnImageAvailableListener(
                reader -> {
                    Image img = reader.acquireLatestImage();
                    if (img == null) return;

                    List<Map<String, Object>> planes = new ArrayList<>();
                    for (Image.Plane plane : img.getPlanes()) {
                        ByteBuffer buffer = plane.getBuffer();

                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes, 0, bytes.length);

                        Map<String, Object> planeBuffer = new HashMap<>();
                        planeBuffer.put("bytesPerRow", plane.getRowStride());
                        planeBuffer.put("bytesPerPixel", plane.getPixelStride());
                        planeBuffer.put("bytes", bytes);

                        planes.add(planeBuffer);
                    }

                    Map<String, Object> imageBuffer = new HashMap<>();
                    imageBuffer.put("width", img.getWidth());
                    imageBuffer.put("height", img.getHeight());
                    imageBuffer.put("format", img.getFormat());
                    imageBuffer.put("planes", planes);

                    imageStreamSink.success(imageBuffer);
                    img.close();
                },
                null);
    }

    private void closeCaptureSession() {
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }
    }

    public void close() {
        closeCaptureSession();

        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (pictureImageJpegReader != null) {
            pictureImageJpegReader.close();
            pictureImageJpegReader = null;
        }
        if (pictureImageRawReader != null) {
            pictureImageRawReader.close();
            pictureImageRawReader = null;
        }
        if (imageStreamReader != null) {
            imageStreamReader.close();
            imageStreamReader = null;
        }
        if (mediaRecorder != null) {
            mediaRecorder.reset();
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }

    public void dispose() {
        close();
        flutterTexture.release();
        orientationEventListener.disable();
    }

    private int getMediaOrientation() {
        final int sensorOrientationOffset =
                (currentOrientation == ORIENTATION_UNKNOWN)
                        ? 0
                        : (isFrontFacing) ? -currentOrientation : currentOrientation;
        return (sensorOrientationOffset + sensorOrientation + 360) % 360;
    }
}
