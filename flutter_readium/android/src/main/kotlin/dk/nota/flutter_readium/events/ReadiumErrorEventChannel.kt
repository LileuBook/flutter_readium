package dk.nota.flutter_readium.events

import io.flutter.plugin.common.BinaryMessenger
import kotlinx.coroutines.launch

/**
 * Event channel for sending error events to Flutter.
 */
class ReadiumErrorEventChannel(messenger: BinaryMessenger) :
    EventChannelWrapper<String>(messenger, "dk.nota.flutter_readium/error") {
    override fun sendEvent(data: String) {
        mainScope.launch {
            eventSink?.success(data)
        }
    }
}
