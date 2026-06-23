using System.Security.Cryptography;

namespace BondClient.Services;

public class E2ECryptoService : IDisposable
{
    private ECDiffieHellman? _ecdh;
    private byte[]? _publicKeyBytes;

    public E2ECryptoService()
    {
        _ecdh = ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);
        _publicKeyBytes = _ecdh.PublicKey.ExportSubjectPublicKeyInfo();
    }

    public string GetPublicKeyBase64()
    {
        return Convert.ToBase64String(_publicKeyBytes!);
    }

    public byte[] DeriveSharedSecret(string otherPartyPublicKeyBase64)
    {
        var otherKeyBytes = Convert.FromBase64String(otherPartyPublicKeyBase64);
        var otherKey = ECDiffieHellman.Create();
        otherKey.ImportSubjectPublicKeyInfo(otherKeyBytes, out _);
        return _ecdh!.DeriveKeyFromHash(otherKey.PublicKey, HashAlgorithmName.SHA256);
    }

    public byte[] DeriveAesKey(byte[] sharedSecret)
    {
        return HKDF.DeriveKey(HashAlgorithmName.SHA256, sharedSecret, 32, salt: null, info: "bond-aes-key"u8.ToArray());
    }

    public static (byte[] ciphertext, byte[] nonce, byte[] tag) EncryptAesGcm(byte[] key, byte[] plaintext)
    {
        var nonce = new byte[12];
        RandomNumberGenerator.Fill(nonce);
        var ciphertext = new byte[plaintext.Length];
        var tag = new byte[16];
        using var aes = new AesGcm(key, 16);
        aes.Encrypt(nonce, plaintext, ciphertext, tag);
        return (ciphertext, nonce, tag);
    }

    public static byte[] DecryptAesGcm(byte[] key, byte[] ciphertext, byte[] nonce, byte[] tag)
    {
        var plaintext = new byte[ciphertext.Length];
        using var aes = new AesGcm(key, 16);
        aes.Decrypt(nonce, ciphertext, tag, plaintext);
        return plaintext;
    }

    public static (byte[] ciphertext, byte[] nonce, byte[] tag) EncryptChunk(byte[] aesKey, Stream input)
    {
        using var ms = new MemoryStream();
        input.CopyTo(ms);
        return EncryptAesGcm(aesKey, ms.ToArray());
    }

    public static void DecryptChunkToStream(byte[] aesKey, byte[] ciphertext, byte[] nonce, byte[] tag, Stream output)
    {
        var plaintext = DecryptAesGcm(aesKey, ciphertext, nonce, tag);
        output.Write(plaintext);
    }

    public static byte[] GenerateAesKey()
    {
        var key = new byte[32];
        RandomNumberGenerator.Fill(key);
        return key;
    }

    public void Dispose()
    {
        _ecdh?.Dispose();
        _ecdh = null;
    }
}
