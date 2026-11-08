package com.mscient.intrapaper.g5;

import java.io.IOException;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import static com.mscient.intrapaper.g5.G5Files.*;

/** Ed25519 possession only. A key supplied with a dossier is not an authenticated human. */
public final class G5Signatures {
    private G5Signatures() { }
    static String verify(Map<String,Object> signed, Map<String,Object> actor) throws IOException {
        String signature = string(signed,"signature");
        if (signature.isEmpty()) return "UNSIGNED";
        try {
            byte[] keyBytes = Base64.getDecoder().decode(string(actor,"public_key"));
            if (keyBytes.length != 44 || !hash(keyBytes).equals(string(actor,"key_sha256"))) throw invalid();
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(keyBytes));
            Signature verifier = Signature.getInstance("Ed25519"); verifier.initVerify(key);
            Map<String,Object> payload = new TreeMap<>(signed); payload.remove("signature"); verifier.update(bytes(payload));
            byte[] sig = Base64.getDecoder().decode(signature);
            if (sig.length != 64 || !verifier.verify(sig)) return "INVALID_SIGNATURE";
            return "VALID_KEY_POSSESSION_ONLY";
        } catch (GeneralSecurityException | IllegalArgumentException e) { return "INVALID_SIGNATURE"; }
    }
}
