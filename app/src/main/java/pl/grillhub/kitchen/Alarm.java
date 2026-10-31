package pl.grillhub.kitchen;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;

/** Builds the looping alarm: our own "new order" chime, or the tablet's system alarm sound. */
final class Alarm {
    private Alarm() {}

    static boolean useSystem(Context c) { return "system".equals(Printer.get(c, "alarm")); }

    static MediaPlayer create(Context c) throws Exception {
        MediaPlayer mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        if (useSystem(c)) {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            mp.setDataSource(c, uri);
        } else {
            AssetFileDescriptor afd = c.getResources().openRawResourceFd(R.raw.new_order);
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
        }
        mp.setLooping(true);
        mp.setVolume(1f, 1f);
        mp.prepare();
        return mp;
    }
}
