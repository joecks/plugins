package io.flutter.plugins.camera;

import java.util.HashMap;
import java.util.Map;

import androidx.annotation.Nullable;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;

class CameraPropertiesMessenger {
    @Nullable
    private EventChannel.EventSink eventSink;

    CameraPropertiesMessenger(BinaryMessenger messenger, long eventChannelId) {
        new EventChannel(messenger, "flutter.io/cameraPlugin/cameraProperties" + eventChannelId)
                .setStreamHandler(
                        new EventChannel.StreamHandler() {
                            @Override
                            public void onListen(Object arguments, EventChannel.EventSink sink) {
                                eventSink = sink;
                            }

                            @Override
                            public void onCancel(Object arguments) {
                                eventSink = null;
                            }
                        });
    }

    void send(int currentIso, long currentExposureTime) {
        if (eventSink == null) {
            return;
        }

        Map<String, String> event = new HashMap<>();
        event.put("iso", String.valueOf(currentIso));
        event.put("exposureTime", String.valueOf(currentExposureTime));
        eventSink.success(event);
    }
}
