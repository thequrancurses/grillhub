package pl.grillhub.kitchen;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;

/** Builds the looping alarm: chime, loud, the tablet's system alarm, or a sound file the user picked. */
final class Alarm {
    private Alarm() {}

    static String mode(Context c) {
        String m = Printer.get(c, "alarm");
        return m.equals("loud") || m.equals("system") || m.equals("custom") ? m : "chime";
    }

    static MediaPlayer create(Context c) throws Exception {
        try {
            return build(c, mode(c));
        } catch (Exception e) {
            return build(c, "chime");          // e.g. the chosen file was deleted
        }
    }

    private static MediaPlayer build(Context c, String mode) throws Exception {
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            if (mode.equals("system")) {
                Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
                if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                mp.setDataSource(c, uri);
            } else if (mode.equals("custom") && !Printer.get(c, "alarm_uri").isEmpty()) {
                mp.setDataSource(c, Uri.parse(Printer.get(c, "alarm_uri")));
            } else {
                AssetFileDescriptor afd = c.getResources().openRawResourceFd(mode.equals("loud") ? R.raw.loud_order : R.raw.new_order);
                mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();
            }
            mp.setLooping(true);
            mp.setVolume(1f, 1f);
            mp.prepare();
            return mp;
        } catch (Exception e) {
            try { mp.release(); } catch (Exception ignore) { /* ignore */ }
            throw e;
        }
    }
}
