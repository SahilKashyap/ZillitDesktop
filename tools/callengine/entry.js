// The page needs the media half of mediasoup and nothing else. Signalling is
// Kotlin's (see OkHttpProtooSocket), so protoo-client is deliberately absent —
// bundling it would ship a second, unused WebSocket client.
import { Device, detectDevice, version } from 'mediasoup-client';

export { Device, detectDevice, version };
