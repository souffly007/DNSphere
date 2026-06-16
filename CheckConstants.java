import android.content.pm.ServiceInfo;
public class CheckConstants {
    public static void main(String[] args) {
        System.out.println("SPECIAL_USE: " + ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        System.out.println("SYSTEM_EXEMPTED: " + ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
    }
}
