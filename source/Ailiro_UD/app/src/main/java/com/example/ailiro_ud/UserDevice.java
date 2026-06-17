package com.example.ailiro_ud;

import static com.example.ailiro_ud.CertGenerator.*;
import static com.example.ailiro_ud.ECC.*;
import static com.example.ailiro_ud.PQCSigner.MLDLSVerify;
import static com.example.ailiro_ud.PQCSigner.MSDLSSign;
import static com.example.ailiro_ud.PQCUtil.AEADdecryptData;
import static com.example.ailiro_ud.PQCUtil.AEADencryptData;
import static com.example.ailiro_ud.PQCUtil.decodeKyberPublicKey;
import static com.example.ailiro_ud.PQCUtil.deriveHSInKeystore;
import static com.example.ailiro_ud.PQCUtil.expandSKDevice;
import static com.example.ailiro_ud.PQCUtil.generateTransAH0;
import static com.example.ailiro_ud.PQCUtil.generateUDnoise;
import static com.example.ailiro_ud.PQKeyManager.byteToDilithiumPublicKey;
import static com.example.ailiro_ud.PQKeyManager.generateAndSecureSession;
import static com.example.ailiro_ud.PQKeyManager.getDilithiumPublicKey;
import static com.example.ailiro_ud.PQKeyManager.getKyberPublicKey;
import static com.example.ailiro_ud.TrustFramework.*;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ProgressBar;
import android.widget.Toast;

import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumPublicKeyParameters;
import org.bouncycastle.pqc.jcajce.provider.dilithium.BCDilithiumPublicKey;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.crypto.SecretKey;

public class UserDevice {

    public static class ApduUtil {
        public static byte getCLA(byte[] apdu) {
            return apdu[0];
        }

        public static byte getINS(byte[] apdu) {
            return apdu[1];
        }

        public static byte getP1(byte[] apdu) {
            return apdu[2];
        }

        public static byte getP2(byte[] apdu) {
            return apdu[3];
        }

        public static int getLc(byte[] apdu) {
            return apdu.length > 4 ? apdu[4] & 0xFF : 0;
        }

        public static byte[] getData(byte[] apdu) {
            int lc = getLc(apdu);
            if (lc == 0 || apdu.length < 5 + lc) return new byte[0];
            byte[] data = new byte[lc];
            System.arraycopy(apdu, 5, data, 0, lc);
            return data;
        }
    }

    private final Context context;
    private static final String ALIAS = "UserDeviceECCKey";
    //CI key
    private PublicKey CIPK;

    private PublicKey devicePK;
    private byte[] rawdevicePK;
    private PrivateKey deviceSK;

    private KeyPair userdevice_ekeypair;
    private byte[] userdevice_epk;

    private PublicKey reader_Pubk;
//    private byte[] reader_PubK;

    private byte[] reader_ePubk;
    private byte[] kdh;
    private byte[] derived_keys_volatite;

    Access_Document access_doc;

    private static final int MAX_APDU_CHUNK = 240; // 一次最多返回240字节（留点空间）

    private static final int Leve_l = 256;
    private int private_mailbox_use = 0;    //if use set 1

    //AUTH0
    private int command_parameters; //0:Expedited_Stantard phase  1:Expedited-Fast phase
    private int authentication_policy;
    private int auth0_response_vendor_extension = 0;    //option 0: no extension  1: has extension
    private byte[] expedited_phase_protocol = new byte[2];
    private byte[] transaction_identifier = new byte[16];
    private byte[] reader_identifier = new byte[32];

    //AUTH1
    private byte[] expeditedSKDevice = new byte[32];
    private byte[] credential_sig;
    private int expedited_device_counter = 0x00000001;
    private int expedited_reader_counter = 0x00000001;
    private byte[] aad = new byte[0];
    private static final String ALIAS_UDECC = "user_device_ecc_key";

    private static final String ALIAS_UDMLDSA = "DilithiumKek";
    private static final String ALIAS_UDKEM = "KyberKek";
    private static final String ALIAS_SSKEY = "SSKey";
    private static final String ALIAS_skDevice = "skDevice";

    private int SIGNALING_BITMAP_AD_RETIEVED = 1;
    private int SIGNALING_BITMAP_RD_RETIEVED = 0;
    private int SIGNALING_BITMAP_STEP_UP_NFC = 0;
    private int SIGNALING_BITMAP_DIF_DATA_MAILBOX = 0;
    private int SIGNALING_BITMAP_READBLE_MAILBOX = 0;
    private int SIGNALING_BITMAP_WRITEBALE_MAILBOX = 0;
    private int SIGNALING_BITMAP_SUPPORT_EXCHANGE = 1;
    private int SIGNALING_BITMAP_SUPORT_FXX_TAG = 0;
    private int SIGNALING_BITMAP_RESERVED = 0;
    private int SIGNALING_BITMAP_SUPORT_UPDATE_DOC_EXPEDITED = 0;
    private int SIGNALING_BITMAP_SUPORT_MALIBOX_FUTURE_SET = 0;
    private int SIGNALING_BITMAP_NOTIFY_SUPORT_EXCHANGE = 0;
    private int SIGNALING_BITMAP_SUPORT_UPDATE_DOC_STEP_UP = 0;

    private byte[] signal_bitmap = new byte[2];
    private byte[] encrypted_payload;
    private byte[] authentication_tag;

    private int key_slot_flag = 0;      //if flag = 0 use full UDpubK , flag = 1, use key_slot
    private byte[] key_slot = new byte[8];  //if command_parameters == 1
    private byte[] encapmassage = new byte[PQCConfig.kyberCiphertextSize()];
    private byte[] UD_noise = new byte[Leve_l/8];
    ByteArrayOutputStream baosATH0ComMes = new ByteArrayOutputStream();
    ByteArrayOutputStream baosATH0ResMes = new ByteArrayOutputStream();
    private byte[] transAH0;

    //UI
    public interface UILayerListener {
        void onShowLoading();
        void onHideLoading();
        void onShowError(String msg);
    }

    public void setUILayerListener(UILayerListener listener) {
        this.uiListener = listener;
    }



    private UILayerListener uiListener;

    public UserDevice(Context context, Access_Document ad) throws Exception {
        this.context = context;

        this.access_doc = ad;
//        getDeviceKey(ALIAS_UDECC);
        getPQDevicePublicKey();
    }


    private void getDeviceKey(String ALIASECC) throws Exception {
        //初始化生成
//        KeyPair devicekeyPair = generateECCKeyPair();
//        PublicKey devicePK = devicekeyPair.getPublic();
//        PrivateKey deviceSK = devicekeyPair.getPrivate();
//        X509Certificate device_cert = generateCert(devicekeyPair);
//        exportCertificateToPem(device_cert, Credential_certificate_path);
//        exportPrivateKeyToPem(deviceSK, Credential_SK_path);

        this.devicePK = KeyStoreManager.getPublicKey(ALIASECC);
        this.deviceSK = KeyStoreManager.getPrivateKey(ALIASECC);
    }

    private void getPQDevicePublicKey( ) throws Exception{
        byte[] rawKeyBytes = getDilithiumPublicKey();

        DilithiumPublicKeyParameters dilithiumPk = new DilithiumPublicKeyParameters(
                PQCConfig.getDilithiumParameters(),
                rawKeyBytes
        );

        this.rawdevicePK = dilithiumPk.getEncoded();
        this.devicePK = byteToDilithiumPublicKey(rawKeyBytes);
    }

    public void setCIPK(PublicKey key) throws Exception {
        this.CIPK = key;
    }

    // 核心数据结构：存储多个AccessCredential
    private final List<AccessCredential> accessCredentials = new ArrayList<>();

    // 添加访问凭证
    public void addAccessCredential(AccessCredential credential) {
        Objects.requireNonNull(credential, "AccessCredential cannot be null");
        if (hasDuplicateReaderIdentifier(credential)) {
            throw new IllegalStateException("Duplicate reader identifier requires special handling");
        }
        accessCredentials.add(credential);
    }

    // 验证读取器签名
    public boolean verifyReaderSignature_by_PK(byte[] signature, PublicKey readerPubKey) {
        // 1. 直接使用reader_PubK
        return false;
    }

    public boolean verifyReaderSignature_by_Cert(byte[] signature, X509Certificate reader_Cert) {
        // 2. 通过CA证书链验证
        return false;
    }

    // 内部检查重复readerIdentifier
    private boolean hasDuplicateReaderIdentifier(AccessCredential newCredential) {
        return accessCredentials.stream()
                .anyMatch(ac -> !Collections.disjoint(
                        ac.getReaderIdentifiers(),
                        newCredential.getReaderIdentifiers()));
    }

    //为了实现HCE的byte[]传输，没有直接用demo中的APDU类型
    public byte[] AUTH0response(byte[] fullApdu) throws Exception {
        if (!PQCConfig.isPQ()) {
            throw new UnsupportedOperationException(
                    "ECC/Classic AUTH0 path is not implemented in UD; set PQCConfig.MODE = Mode.PQ");
        }

        // ========== 1. TLV 解析阶段 ==========
        int inc = 0;

        if ((fullApdu[inc++] & 0xFF) != 0x41 || (fullApdu[inc++] & 0xFF) != 0x01) {
            throw new IllegalArgumentException("command_parameters is not valid");
        } else {
            if (fullApdu[inc++] == 0x00) {
                this.command_parameters = 0;
            } else {
                this.command_parameters = 1;
            }
        }

        if ((fullApdu[inc++] & 0xFF) != 0x42 || (fullApdu[inc++] & 0xFF) != 0x01) {
            throw new IllegalArgumentException("authentication_policy is not valid");
        } else {
            this.authentication_policy = (fullApdu[inc++] & 0xFF);
        }

        if ((fullApdu[inc++] & 0xFF) != 0x5C || (fullApdu[inc++] & 0xFF) != 0x02) {
            throw new IllegalArgumentException("expedited_phase_protocol_version is not valid");
        } else {
            System.arraycopy(fullApdu, inc, expedited_phase_protocol, 0, 2);
            inc = inc + 2;
        }

        if ((fullApdu[inc++] & 0xFF) == 0x87) {
            int tag =  fullApdu[inc++] & 0xFF;
            int pklen = 0;
            if(tag == 0x81) {        //1字节扩展
                pklen = fullApdu[inc++] & 0xFF;
            }else if(tag == 0x82){
                pklen = ((fullApdu[inc++] & 0xFF) << 8) + (fullApdu[inc++] & 0xFF);
            }else{
                pklen = tag;
            }
            byte[] t_epk = new byte[pklen];
            System.arraycopy(fullApdu, inc, t_epk, 0, pklen);
            this.reader_ePubk = t_epk;
            this.baosATH0ComMes.write(this.reader_ePubk);
            inc = inc + pklen;
        }

        if ((fullApdu[inc++] & 0xFF) == 0x4C) {
            int translen = fullApdu[inc++] & 0xFF;
            byte[] t_transid = new byte[translen];
            System.arraycopy(fullApdu, inc, t_transid, 0, translen);
            this.transaction_identifier = t_transid;
            this.baosATH0ComMes.write(this.transaction_identifier);
            inc = inc + translen;
        }

        if ((fullApdu[inc++] & 0xFF) == 0x4D) {
            int readlen = fullApdu[inc++] & 0xFF;
            byte[] t_readerid = new byte[readlen];
            System.arraycopy(fullApdu, inc, t_readerid, 0, readlen);
            this.reader_identifier = t_readerid;
            this.baosATH0ComMes.write(this.reader_identifier);
            inc = inc + readlen;
        }

        // ========== 2. 构造响应数据 ==========
        List<Byte> response = new ArrayList<>();

        // User Device ephemeral public key
//        this.userdevice_ekeypair = generateECCKeyPair();
//        this.userdevice_epk = CertGenerator.extractPublicKey(userdevice_ekeypair.getPublic().getEncoded());

        // KEM 做安全encapsulate
        PublicKey reader_ePubk = decodeKyberPublicKey(this.reader_ePubk);
        this.encapmassage = generateAndSecureSession(reader_ePubk,ALIAS_SSKEY);

        //这里换用kyber 768以后不能一个字节表示长度，根据BER-TLV（RFC 7816）规范，需要“长格式”编码，0x81表示后续一个字节扩展（127-255），0x82表示后续2个字节扩展（>255）
        response.add((byte) 0x86);
        int len = this.encapmassage.length; // 1088

            // 根据 ISO 7816/BER-TLV 规则：
        if (len <= 127) {
            // 短格式：直接写入长度 (例如 ECDSA 的 65)
            response.add((byte) len);
        } else if (len <= 255) {
            // 长格式 (1字节扩展)：0x81 + 长度 (例如长度 200)
            response.add((byte) 0x81);
            response.add((byte) len);
        } else {
            // 长格式 (2字节扩展)：0x82 + 高位 + 低位 (Kyber 1184 属于这种情况)
            // 0x82 的意思是：第7位为1(表示长格式)，后7位的值为2(表示由接下来的2个字节代表长度)
            response.add((byte) 0x82);

            // 写入长度的高位
            response.add((byte) ((len >> 8) & 0xFF));

            // 写入长度的低位
            response.add((byte) (len & 0xFF));
        }

        for (byte b : this.encapmassage) response.add(b);
        this.baosATH0ResMes.write(this.encapmassage);

        // 额外需要一个UD_noise作为随机噪声 tag - 0x43
        UD_noise = generateUDnoise(UD_noise.length);
        response.add((byte) 0x43);
        response.add((byte)UD_noise.length);
        for (byte b : this.UD_noise) response.add(b);
        this.baosATH0ResMes.write(this.UD_noise);

        // expedited phase
        if (command_parameters == 1) {
            response.add((byte) 0x9D);
            response.add((byte) 0x64);
        }

        // vendor extension
        if (auth0_response_vendor_extension == 1) {
            response.add((byte) 0xB2);
        }

        if (this.encapmassage.length != PQCConfig.kyberCiphertextSize()) {
            throw new IllegalArgumentException(
                    "Kyber encapsulation length mismatch: expected "
                            + PQCConfig.kyberCiphertextSize()
                            + " bytes for Kyber" + PQCConfig.KYBER_LEVEL
                            + ", got " + this.encapmassage.length);
        }

        // ========== 3. 分包发送逻辑 ==========
        byte[] total = new byte[response.size()];
        for (int i = 0; i < response.size(); i++) total[i] = response.get(i);

        int offset = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        while (offset < total.length) {
            int remain = total.length - offset;
            int chunkSize = Math.min(remain, MAX_APDU_CHUNK);
            byte[] chunk = Arrays.copyOfRange(total, offset, offset + chunkSize);
            out.write(chunk);
            //这里只做消息逻辑不做状态逻辑
//            if (offset + chunkSize == total.length) {
//                // 最后一包
//                out.write((byte) 0x90);
//                out.write((byte) 0x00);
//            } else {
//                // 中间包，返回 61xx，表示“还有剩余字节”
//                int left = remain - chunkSize;
//                out.write((byte) 0x61);
//                out.write((byte) Math.min(left, 0xFF));
//            }

            offset += chunkSize;
        }

        byte[] resp = out.toByteArray();
        return resp;
    }


    public byte[] LOADCERT_Response(byte[] fullCertData) throws Exception {
        if (!PQCConfig.isPQ()) {
            throw new UnsupportedOperationException(
                    "ECC/Classic LOADCERT path is not implemented in UD; set PQCConfig.MODE = Mode.PQ");
        }

        if (fullCertData == null) {
            throw new IllegalArgumentException("APDU list is empty");
        }

        Map<String,byte[]> map = parseProfile0000(fullCertData);

        X509Certificate reader_cert = decompressToX509Certificate(map);


        byte[] certbyte = reader_cert.getEncoded();
        System.out.println(Arrays.toString(certbyte));

        try {
            reader_cert.verify(this.CIPK, "BCPQC");
            this.reader_Pubk = reader_cert.getPublicKey();
        } catch (Exception e) {
            e.printStackTrace();
            throw new IllegalArgumentException("cert verified failed");
        }

        List<Byte> response = new ArrayList<>();

        response.add((byte)0x90);
        response.add((byte)0x00);

        byte[] result = new byte[response.size()];
        for (int i = 0; i < response.size(); i++) {
            result[i] = response.get(i);
        }

        return result;
    }

    public byte[] AUTH1response(byte[] fullApdu) throws Exception {
        if (!PQCConfig.isPQ()) {
            throw new UnsupportedOperationException(
                    "ECC/Classic AUTH1 path is not implemented in UD; set PQCConfig.MODE = Mode.PQ");
        }

        int inc = 0;

        byte[] readersignature;

        if( ( (fullApdu[inc++] & 0xFF) == 0x41 ) && ((fullApdu[inc++] & 0xFF) == 0x01) ){
            this.command_parameters = fullApdu[inc++] & 0xFF;
        }else{
            throw new IllegalArgumentException("command_parameters is not valid: " + fullApdu[inc++]);
        }

        if( (fullApdu[inc++] & 0xFF) == 0x9E ){
            int len_tag = fullApdu[inc++] & 0xFF;
            int len = 0;
            if(len_tag == 0x81){        //1字节扩展
                len = fullApdu[inc++] & 0xFF;
            }else if(len_tag == 0x82){  //2字节扩展
                len = ((fullApdu[inc++] & 0xFF) << 8) + (fullApdu[inc++] & 0xFF);
            }else{
                len = len_tag;
            }
            byte[] signature = new byte[len];
            System.arraycopy(fullApdu, inc, signature, 0, len);
            inc = inc + len;
            readersignature = signature;
        }else{
            throw new IllegalArgumentException("readersignature is not valid! ");
        }

        if( ((inc+1)<fullApdu.length) && ((fullApdu[inc++] & 0xFF) == 0x90) ){
            int len = fullApdu[inc++] & 0xFF;
            //readerCert
        }

        byte[] AUTH0ComByte = baosATH0ComMes.toByteArray();
        byte[] AUTH0ResByte = baosATH0ResMes.toByteArray();
        byte[] transByte = new byte[AUTH0ComByte.length+AUTH0ResByte.length];
        System.arraycopy(AUTH0ComByte, 0, transByte, 0, AUTH0ComByte.length);
        System.arraycopy(AUTH0ResByte, 0, transByte, AUTH0ComByte.length, AUTH0ResByte.length);
//        System.out.println(Arrays.toString(generateTransAH0(AUTH0ComByte)));
//        System.out.println(Arrays.toString(generateTransAH0(AUTH0ResByte)));
//        System.out.println(Arrays.toString(transByte));
        this.transAH0 = generateTransAH0(transByte);
//        System.out.println(Arrays.toString(this.transAH0));

        List<Byte> toauthdata = new ArrayList<>();

        toauthdata.add((byte) 0x4D);  //reader_identifier
        toauthdata.add((byte) 0x20);  //lenth = 32
        for (byte b : this.reader_identifier) toauthdata.add(b);

        toauthdata.add((byte) 0x4A);    //transaction hash message (SHAKE256-512 → 64B)
        toauthdata.add((byte) 0x40);    //length = 64
        for (byte b : this.transAH0) toauthdata.add(b);

        toauthdata.add((byte) 0x4C);  //transaction_identifier
        toauthdata.add((byte) 0x10);  //lenth = 16
        for (byte b : this.transaction_identifier) toauthdata.add(b);

        toauthdata.add((byte) 0x93);  //usage
        toauthdata.add((byte) 0x04);  //lenth = 4
        toauthdata.add((byte) 0x41);
        toauthdata.add((byte) 0x5D);
        toauthdata.add((byte) 0x95);
        toauthdata.add((byte) 0x69);

        byte[] toauthdataBytes = new byte[toauthdata.size()];
        for (int i = 0; i < toauthdata.size(); i++) toauthdataBytes[i] = toauthdata.get(i);

        boolean verifyRes = MLDLSVerify(toauthdataBytes,readersignature,this.reader_Pubk);
        int verifytag = 0;

        if(!verifyRes){
            throw new Exception("verify signature failed");
        } else {
            verifytag = 1;
        }

        //auth0_command_vendor_extension
        //auth0_response_vendor_extension

        byte[] HS = deriveHSInKeystore(ALIAS_SSKEY,this.transAH0);
        if(!expandSKDevice(HS,this.transAH0,ALIAS_skDevice)){
            throw new Exception("SKDevice generated failed");
        }

        List<Byte> reauthdata = new ArrayList<>();

        reauthdata.add((byte) 0x4D);  //reader_identifier
        reauthdata.add((byte) 0x20);  //lenth = 32
        for (byte b : this.reader_identifier) reauthdata.add(b);

        reauthdata.add((byte) 0x4A);    //transaction hash message (SHAKE256-512 → 64B)
        reauthdata.add((byte) 0x40);    //length = 64
        for (byte b : this.transAH0) reauthdata.add(b);

        reauthdata.add((byte) 0x4C);  //transaction_identifier
        reauthdata.add((byte) 0x10);  //lenth = 16
        for (byte b : this.transaction_identifier) reauthdata.add(b);

        reauthdata.add((byte) 0x93);  //usage
        reauthdata.add((byte) 0x04);  //lenth = 4
        reauthdata.add((byte) 0x4E);
        reauthdata.add((byte) 0x88);
        reauthdata.add((byte) 0x7B);
        reauthdata.add((byte) 0x4C);

        byte[] reauthdataBytes = new byte[reauthdata.size()];
        for (int i = 0; i < reauthdata.size(); i++) reauthdataBytes[i] = reauthdata.get(i);

//        byte[] sig = MSDLSSign(reauthdataBytes, this.context);
        // 开启子线程执行同步方法，避免死锁

//        if (uiListener != null) {
//            uiListener.onShowLoading(); // 触发 Activity 显示加载弹窗
//        }


        byte[] res = MSDLSSign(reauthdataBytes, context);
//        boolean v = MLDLSVerify(reauthdataBytes,res,this.devicePK);

        this.credential_sig = res;

//        this.credential_sig = derToRaw(sig);

        List<Byte> respon_data = new ArrayList<>();

        Log.d("PQC_DEBUG_UD", "--- UD公钥检查 ---");
        Log.d("PQC_DEBUG_UD", "Counter: " + Arrays.toString(this.devicePK.getEncoded()));

        DilithiumPublicKeyParameters pubParams = new DilithiumPublicKeyParameters(
                PQCConfig.getDilithiumParameters(), this.rawdevicePK);
        PublicKey t = new BCDilithiumPublicKey(pubParams);

        if(this.command_parameters == 0){
            if(key_slot_flag == 0){
                respon_data.add((byte) 0x5A);       //Access Credential long term Pubkey
                int pklen = this.rawdevicePK.length;
                if (pklen <= 127) {
                    // 短格式：直接写入长度 (例如 ECDSA 的 65)
                    respon_data.add((byte) pklen);
                } else if (pklen <= 255) {
                    //长格式 (1字节扩展)：0x81 + 长度 (例如长度 200)
                    respon_data.add((byte) 0x81);
                    respon_data.add((byte) pklen);
                } else {
                    // 长格式 (2字节扩展)：0x82 + 高位 + 低位 (Dilithium 1952 属于这种情况)
                    // 0x82
                    respon_data.add((byte) 0x82);
                    respon_data.add((byte) ((pklen >> 8) & 0xFF));
                    respon_data.add((byte) (pklen & 0xFF));
                }
                for (byte b : this.rawdevicePK) respon_data.add(b);
            }else{
                respon_data.add((byte) 0x4E);       //key_slot
                respon_data.add((byte) 0x08);       //lenth = 8
                for (byte b : this.key_slot) respon_data.add(b);
            }
        }

        respon_data.add((byte) 0x9E);           //  User Device signature
        int siglen = this.credential_sig.length;
        if (siglen <= 127) {
            respon_data.add((byte) siglen);
        } else if (siglen <= 255) {
            respon_data.add((byte) 0x81);
            respon_data.add((byte) siglen);
        } else {
            respon_data.add((byte) 0x82);
            respon_data.add((byte) ((siglen >> 8) & 0xFF));
            respon_data.add((byte) (siglen & 0xFF));
        }
        for (byte b : this.credential_sig) respon_data.add(b);

        if(this.private_mailbox_use == 1){
            respon_data.add((byte) 0x4B);
            // private_mailbox_data_subset
        }

        int signalingBitmap = 0;

        signalingBitmap |= (SIGNALING_BITMAP_AD_RETIEVED & 0x01) << 0;
        signalingBitmap |= (SIGNALING_BITMAP_RD_RETIEVED & 0x01) << 1;
        signalingBitmap |= (SIGNALING_BITMAP_STEP_UP_NFC & 0x01) << 2;
        signalingBitmap |= (SIGNALING_BITMAP_DIF_DATA_MAILBOX & 0x01) << 3;
        signalingBitmap |= (SIGNALING_BITMAP_READBLE_MAILBOX & 0x01) << 4;
        signalingBitmap |= (SIGNALING_BITMAP_WRITEBALE_MAILBOX & 0x01) << 5;
        signalingBitmap |= (SIGNALING_BITMAP_SUPPORT_EXCHANGE & 0x01) << 6;
        signalingBitmap |= (SIGNALING_BITMAP_SUPORT_FXX_TAG & 0x01) << 7;
        signalingBitmap |= (SIGNALING_BITMAP_RESERVED & 0x01) << 8;
        signalingBitmap |= (SIGNALING_BITMAP_SUPORT_UPDATE_DOC_EXPEDITED & 0x01) << 9;
        signalingBitmap |= (SIGNALING_BITMAP_SUPORT_MALIBOX_FUTURE_SET & 0x01) << 10;
        signalingBitmap |= (SIGNALING_BITMAP_NOTIFY_SUPORT_EXCHANGE & 0x01) << 11;
        signalingBitmap |= (SIGNALING_BITMAP_SUPORT_UPDATE_DOC_STEP_UP & 0x01) << 12;

        // Wire format: byte[0] = high byte (bits 8-15), byte[1] = low byte (bits 0-7).
        // Reader parses as ((b[0]&0xFF)<<8) | (b[1]&0xFF) — see Reader.java getAUTH1resp.
        this.signal_bitmap[0] = (byte) ((signalingBitmap >> 8) & 0xFF);
        this.signal_bitmap[1] = (byte) (signalingBitmap & 0xFF);

        respon_data.add((byte) 0x5E);   //signaling_bitmap;
        respon_data.add((byte) 0x02);   //length = 2
        for (byte b : this.signal_bitmap) respon_data.add(b);

        //credential_signed_timestamp

        //revocation_signed_timestamp

        byte[] unencrypted_payload = new byte[respon_data.size()];

        for (int i = 0; i < respon_data.size(); i++) unencrypted_payload[i] = respon_data.get(i);

        //PQC AEAD
        byte[] encrypedpayloadAndTag = AEADencryptData(this.ALIAS_skDevice,unencrypted_payload,this.aad,this.expedited_device_counter);
        int enctag = 0;
        if(encrypedpayloadAndTag != null){
            enctag = 1;
        }else {
            throw new Exception("AEAD encrypt failed");
        }

//        byte[] re = AEADdecryptData(this.ALIAS_skDevice,encrypedpayloadAndTag,this.aad,this.expedited_device_counter);
//        boolean isEqual = java.util.Arrays.equals(re, unencrypted_payload);
//        android.util.Log.d("decrypres", "dec: " + isEqual);
        //ECC GCM AES
//        byte[][] result = GCM_AES_encrypt(unencrypted_payload,this.expeditedSKDevice,intToBigEndianBytes(this.expedited_device_counter),this.aad);
//        this.encrypted_payload = result[0];
//        this.authentication_tag = result[1];

        //        byte[] respon_byte = concatBytes(this.encrypted_payload,this.authentication_tag);
        byte[] respon_byte = encrypedpayloadAndTag;


        //increment device counter after generated encrypted_payload and after having successfully verified the reader signature
        if(verifytag==1 && enctag==1){
            this.expedited_device_counter++;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();



        int offset = 0;
        while (offset < respon_byte.length) {
            int remain = respon_byte.length - offset;
            int chunkSize = Math.min(remain, MAX_APDU_CHUNK);
            byte[] chunk = Arrays.copyOfRange(respon_byte, offset, offset + chunkSize);
            out.write(chunk);

//            if (offset + chunkSize == respon_byte.length) {
//                // 最后一包
//                out.write((byte) 0x90);
//                out.write((byte) 0x00);
//            } else {
//                // 中间包，返回 61xx，表示“还有剩余字节”
//                int left = remain - chunkSize;
//                out.write((byte) 0x61);
//                out.write((byte) Math.min(left, 0xFF));
//            }

            offset += chunkSize;
        }
        return out.toByteArray();

    }



    // AccessCredential嵌套类
    public static class AccessCredential {
        private final KeyPair keyPair; // 唯一密钥对
        private final Set<String> readerIdentifiers; // 一个或多个读取器标识符
        private final X509Certificate credentialCertificate; // 可选证书
        private final byte[] accessDocument; // 签名后的访问文档
        private final byte[] revocationDocument; // 签名后的撤销文档

        public AccessCredential(KeyPair keyPair,
                                Collection<String> readerIdentifiers,
                                X509Certificate credentialCertificate,
                                byte[] accessDocument,
                                byte[] revocationDocument) {
            this.keyPair = Objects.requireNonNull(keyPair);
            this.readerIdentifiers = Set.copyOf(Objects.requireNonNull(readerIdentifiers));
            this.credentialCertificate = credentialCertificate; // 允许null
            this.accessDocument = accessDocument != null ? accessDocument.clone() : null;
            this.revocationDocument = revocationDocument != null ? revocationDocument.clone() : null;

        }

        // Getter方法
        public Set<String> getReaderIdentifiers() {
            return readerIdentifiers;
        }

        public Optional<byte[]> getAccessDocument() {
            return Optional.ofNullable(accessDocument);
        }

        public Optional<byte[]> getRevocationDocument() {
            return Optional.ofNullable(revocationDocument);
        }
    }





}
