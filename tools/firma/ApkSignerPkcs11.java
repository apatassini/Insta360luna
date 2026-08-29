import java.security.Provider;
import java.security.Security;

/**
 * apksigner non riesce a istanziare SunPKCS11 da solo: su JDK 9+ il modulo
 * jdk.crypto.cryptoki non esporta sun.security.pkcs11 e il costruttore che
 * accetta il file di configurazione non esiste piu'. Qui il provider viene
 * configurato con l'API supportata (Provider.configure) e registrato, poi si
 * delega ad apksigner passandogli il provider per nome.
 */
public class ApkSignerPkcs11 {
    public static void main(String[] args) throws Exception {
        String cfg = System.getProperty("pkcs11.config");
        if (cfg == null) throw new IllegalStateException("manca -Dpkcs11.config=<file .cfg>");
        Provider p = Security.getProvider("SunPKCS11").configure(cfg);
        Security.addProvider(p);
        System.out.println("[pkcs11] provider registrato: " + p.getName());
        com.android.apksigner.ApkSignerTool.main(args);
    }
}
