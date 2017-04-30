package ca.dungeons.sensordump;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

public class AudioLogger {

    boolean isRunning = false; // Indicates if recording / playback should stop

    float loudness = 0;
    float frequency = 0;

    private final int SAMPLE_RATE = 44100; // The sampling rate

    private AudioRecord record;

    void startRecording() {

        Log.i("Audio", "Audio recording starting.");

        // Don't run multiple instances of the thread
        if(isRunning) {
            return;
        } else {
            isRunning = true;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                // buffer size in bytes
                int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);

                if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                    bufferSize = SAMPLE_RATE * 2;
                }

                short[] audioBuffer = new short[bufferSize / 2];

                record = new AudioRecord(MediaRecorder.AudioSource.DEFAULT,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);

                if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e("Audio Error", "Can't record audio.");
                    return;
                }

                record.startRecording();

                long shortsRead = 0;
                while (isRunning) {
                    record.read(audioBuffer, 0, audioBuffer.length);

                    float lowest = 0;
                    float highest = 0;
                    int zeroes = 0;
                    int last_value = 0;

                    for (int i = 0; i < audioBuffer.length; i++) {

                        // Detect lowest in sample
                        if (audioBuffer[i] < lowest) {
                            lowest = audioBuffer[i];
                        }

                        // Detect highest in sample
                        if (audioBuffer[i] > highest) {
                            highest = audioBuffer[i];
                        }

                        // Down and coming up
                        if(audioBuffer[i] > 0 && last_value < 0) {
                            zeroes++;
                        }

                        // Up and down
                        if(audioBuffer[i] < 0 && last_value > 0) {
                            zeroes++;
                        }

                        last_value = audioBuffer[i];
                    }

                    // Calculate highest and lowest peak difference as a % of the max possible
                    // value
                    loudness = (highest - lowest) / 65536 * 100;

                    // Take the count of the peaks in the time that we had based on the sample
                    // rate to calculate frequency
                    float seconds = (float) audioBuffer.length / (float) SAMPLE_RATE;;
                    frequency = (float) zeroes / seconds / 2;
                }

                record.stop();
                record.release();
                Log.i("Audio", "Audio recording stopped.");
            }
        }).start();
    }

    void stopRecording() {
        Log.i("Audio", "Audio recording stopping.");
        isRunning = false;
    }

}
