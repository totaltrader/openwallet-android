package com.coinomi.core.wallet;

import com.coinomi.core.coins.CoinType;
import com.coinomi.core.network.interfaces.BlockchainConnection;
import com.coinomi.core.network.interfaces.ConnectionEventListener;
import com.coinomi.core.protos.Protos;
import com.google.bitcoin.core.Address;
import com.google.bitcoin.core.Coin;
import com.google.bitcoin.core.InsufficientMoneyException;
import com.google.bitcoin.crypto.DeterministicHierarchy;
import com.google.bitcoin.crypto.DeterministicKey;
import com.google.bitcoin.crypto.HDKeyDerivation;
import com.google.bitcoin.crypto.KeyCrypter;
import com.google.bitcoin.crypto.MnemonicCode;
import com.google.bitcoin.crypto.MnemonicException;
import com.google.bitcoin.store.UnreadableWalletException;
import com.google.bitcoin.utils.Threading;
import com.google.bitcoin.wallet.DeterministicSeed;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.crypto.params.KeyParameter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * @author Giannis Dzegoutanis
 */
final public class Wallet implements ConnectionEventListener {
    private static final Logger log = LoggerFactory.getLogger(Wallet.class);
    public static int ENTROPY_SIZE_DEBUG = -1;

    private final ReentrantLock lock = Threading.lock("KeyChain");

    @GuardedBy("lock") private final LinkedHashMap<CoinType, WalletPocket> pockets;

    @Nullable private DeterministicSeed seed;
    private DeterministicKey masterKey;

    protected volatile WalletFiles vFileManager;

    // FIXME, make multi account capable
    private final static int ACCOUNT_ZERO = 0;

    @Nullable transient BlockchainConnection blockchainConnection;
    private int version;

    public Wallet(List<String> mnemonic) throws MnemonicException {
        this(mnemonic, null);
    }

    public Wallet(List<String> mnemonic, @Nullable String password) throws MnemonicException {
        MnemonicCode.INSTANCE.check(mnemonic);
        password = password == null ? "" : password;

        seed = new DeterministicSeed(mnemonic, password, 0);
        masterKey = HDKeyDerivation.createMasterPrivateKey(seed.getSeedBytes());
        pockets = new LinkedHashMap<CoinType, WalletPocket>();
    }

    public Wallet(DeterministicKey masterKey, @Nullable DeterministicSeed seed) {
        this.seed = seed;
        this.masterKey = masterKey;
        pockets = new LinkedHashMap<CoinType, WalletPocket>();
    }

    public static List<String> generateMnemonic(int entropyBits) throws IOException {
        byte[] entropy;
        if (ENTROPY_SIZE_DEBUG > 0) {
            entropy = new byte[ENTROPY_SIZE_DEBUG];
        } else {
            entropy = new byte[entropyBits / 8];
        }

        SecureRandom sr = new SecureRandom();
        sr.nextBytes(entropy);

        List<String> mnemonic;
        try {
            mnemonic = MnemonicCode.INSTANCE.toMnemonic(entropy);
        } catch (MnemonicException.MnemonicLengthException e) {
            throw new RuntimeException(e); // should not happen, we have 16bytes of entropy
        }

        return mnemonic;
    }

    public void createCoinPockets(List<CoinType> coins, boolean generateAllKeys) {
        for (CoinType coin : coins) {
            log.info("Creating coin pocket for {}", coin);
            WalletPocket pocket = getPocket(coin);
            if (generateAllKeys) {
                pocket.maybeInitializeAllKeys();
            }
        }
    }

    public WalletPocket getPocket(CoinType coinType) {
        lock.lock();
        try {
            if (!pockets.containsKey(coinType)) {
                createPocket(coinType);
            }
            return pockets.get(coinType);
        } finally {
            lock.unlock();
        }
    }

    public List<WalletPocket> getPockets() {
        lock.lock();
        try {
            return ImmutableList.copyOf(pockets.values());
        } finally {
            lock.unlock();
        }
    }

    private void createPocket(CoinType coinType) {
        DeterministicHierarchy hierarchy = new DeterministicHierarchy(masterKey);
        DeterministicKey rootKey = hierarchy.get(coinType.getBip44Path(ACCOUNT_ZERO), false, true);
        addPocket(new WalletPocket(rootKey, coinType, masterKey.getKeyCrypter()));
    }


    public void addPocket(WalletPocket pocket) {
        checkState(!pockets.containsKey(pocket.getCoinType()), "Cannot replace an existing wallet pocket");
        //TODO check if key crypter is the same
        pockets.put(pocket.getCoinType(), pocket);
        pocket.setWallet(this);
    }

    /**
     * Make the wallet generate all the needed lookahead keys if needed
     */
    public void maybeInitializeAllPockets() {
        for (WalletPocket pocket : getPockets()) {
            pocket.maybeInitializeAllKeys();
        }
    }

    public DeterministicKey getMasterKey() {
        lock.lock();
        try {
            return masterKey;
        } finally {
            lock.unlock();
        }
    }

    /** Returns a list of words that represent the seed or null if this chain is a watching chain. */
    @Nullable
    public List<String> getMnemonicCode() {
        if (seed == null) return null;

        lock.lock();
        try {
            return seed.getMnemonicCode();
        } finally {
            lock.unlock();
        }
    }

    @Nullable
    public DeterministicSeed getSeed() {
        lock.lock();
        try {
            return seed;
        } finally {
            lock.unlock();
        }
    }

    List<CoinType> getCoinTypes() {
        lock.lock();
        try {
            return ImmutableList.copyOf(pockets.keySet());
        } finally {
            lock.unlock();
        }
    }

    /** Returns the {@link KeyCrypter} in use or null if the key chain is not encrypted. */
    @Nullable
    public KeyCrypter getKeyCrypter() {
        lock.lock();
        try {
            return masterKey.getKeyCrypter();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onConnection(BlockchainConnection blockchainConnection) {
        this.blockchainConnection = blockchainConnection;
        for (CoinType coin : getCoinTypes()) {
            WalletPocket pocket = getPocket(coin);
            if (blockchainConnection != null) {
                pocket.onConnection(blockchainConnection);
            }
        }
    }

    @Override
    public void onDisconnect() {
        this.blockchainConnection = null;
    }


    public void sendCoins(Address address, Coin amount) throws InsufficientMoneyException, IOException {
        WalletPocket pocket = getPocket((CoinType) address.getParameters());

        pocket.sendCoins(address, amount);
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public int getVersion() {
        return version;
    }

    public void refresh() {
        lock.lock();
        try {
            for (WalletPocket pocket : getPockets()) {
                pocket.refresh();
            }
            saveNow();
        } finally {
            lock.unlock();
        }
    }

    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // Serialization support
    //
    //////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @VisibleForTesting
    Protos.Wallet toProtobuf() {
        lock.lock();
        try {
            return WalletProtobufSerializer.toProtobuf(this);
        } finally {
            lock.unlock();
        }
    }
    /**
     * Returns a wallet deserialized from the given file.
     */
    public static Wallet loadFromFile(File f) throws UnreadableWalletException {
        try {
            FileInputStream stream = null;
            try {
                stream = new FileInputStream(f);
                return loadFromFileStream(stream);
            } finally {
                if (stream != null) stream.close();
            }
        } catch (IOException e) {
            throw new UnreadableWalletException("Could not open file", e);
        }
    }

    /**
     * Returns a wallet deserialized from the given input stream.
     */
    public static Wallet loadFromFileStream(InputStream stream) throws UnreadableWalletException {
        return WalletProtobufSerializer.readWallet(stream);
    }

    /**
     * Uses protobuf serialization to save the wallet to the given file stream. To learn more about this file format, see
     * {@link WalletProtobufSerializer}.
     */
    public void saveToFileStream(OutputStream f) throws IOException {
        lock.lock();
        try {
            WalletProtobufSerializer.writeWallet(this, f);
        } finally {
            lock.unlock();
        }
    }

    /** Saves the wallet first to the given temp file, then renames to the dest file. */
    public void saveToFile(File temp, File destFile) throws IOException {
        FileOutputStream stream = null;
        lock.lock();
        try {
            stream = new FileOutputStream(temp);
            saveToFileStream(stream);
            // Attempt to force the bits to hit the disk. In reality the OS or hard disk itself may still decide
            // to not write through to physical media for at least a few seconds, but this is the best we can do.
            stream.flush();
            stream.getFD().sync();
            stream.close();
            stream = null;
            if (!temp.renameTo(destFile)) {
                throw new IOException("Failed to rename " + temp + " to " + destFile);
            }
        } catch (RuntimeException e) {
            log.error("Failed whilst saving wallet", e);
            throw e;
        } finally {
            lock.unlock();
            if (stream != null) {
                stream.close();
            }
            if (temp.exists()) {
                log.warn("Temp file still exists after failed save.");
            }
        }
    }

    /**
     * <p>Sets up the wallet to auto-save itself to the given file, using temp files with atomic renames to ensure
     * consistency. After connecting to a file, you no longer need to save the wallet manually, it will do it
     * whenever necessary. Protocol buffer serialization will be used.</p>
     *
     * <p>If delayTime is set, a background thread will be created and the wallet will only be saved to
     * disk every so many time units. If no changes have occurred for the given time period, nothing will be written.
     * In this way disk IO can be rate limited. It's a good idea to set this as otherwise the wallet can change very
     * frequently, eg if there are a lot of transactions in it or during block sync, and there will be a lot of redundant
     * writes. Note that when a new key is added, that always results in an immediate save regardless of
     * delayTime. <b>You should still save the wallet manually when your program is about to shut down as the JVM
     * will not wait for the background thread.</b></p>
     *
     * <p>An event listener can be provided. If a delay >0 was specified, it will be called on a background thread
     * with the wallet locked when an auto-save occurs. If delay is zero or you do something that always triggers
     * an immediate save, like adding a key, the event listener will be invoked on the calling threads.</p>
     *
     * @param f The destination file to save to.
     * @param delayTime How many time units to wait until saving the wallet on a background thread.
     * @param timeUnit the unit of measurement for delayTime.
     * @param eventListener callback to be informed when the auto-save thread does things, or null
     */
    public WalletFiles autosaveToFile(File f, long delayTime, TimeUnit timeUnit,
                                      @Nullable WalletFiles.Listener eventListener) {
        lock.lock();
        try {
            checkState(vFileManager == null, "Already auto saving this wallet.");
            WalletFiles manager = new WalletFiles(this, f, delayTime, timeUnit);
            if (eventListener != null) {
                manager.setListener(eventListener);
            }
            vFileManager = manager;
            return manager;
        } finally {
            lock.unlock();
        }
    }

    /**
     * <p>
     * Disables auto-saving, after it had been enabled with
     * {@link Wallet#autosaveToFile(java.io.File, long, java.util.concurrent.TimeUnit, com.coinomi.core.wallet.WalletFiles.Listener)}
     * before. This method blocks until finished.
     * </p>
     */
    public void shutdownAutosaveAndWait() {
        lock.lock();
        try {
            WalletFiles files = vFileManager;
            vFileManager = null;
            checkState(files != null, "Auto saving not enabled.");
            files.shutdownAndWait();
        } finally {
            lock.unlock();
        }
    }

    /** Requests an asynchronous save on a background thread */
    public void saveLater() {
        lock.lock();
        try {
            WalletFiles files = vFileManager;
            if (files != null) {
                files.saveLater();
            }
        } finally {
            lock.unlock();
        }
    }

    /** If auto saving is enabled, do an immediate sync write to disk ignoring any delays. */
    public void saveNow() {
        lock.lock();
        try {
            WalletFiles files = vFileManager;
            if (files != null) {
                try {
                    files.saveNow();  // This calls back into saveToFile().
                } catch (IOException e) {
                    // Can't really do much at this point, just let the API user know.
                    log.error("Failed to save wallet to disk!", e);
                    Thread.UncaughtExceptionHandler handler = Threading.uncaughtExceptionHandler;
                    if (handler != null)
                        handler.uncaughtException(Thread.currentThread(), e);
                }
            }
        } finally {
            lock.unlock();
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    //
    // Encryption support
    //
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private boolean isEncrypted() {
        return masterKey.isEncrypted();
    }

    /**
     * Encrypt the keys in the group using the KeyCrypter and the AES key. A good default KeyCrypter to use is
     * {@link com.google.bitcoin.crypto.KeyCrypterScrypt}.
     *
     * @throws com.google.bitcoin.crypto.KeyCrypterException Thrown if the wallet encryption fails for some reason,
     *         leaving the group unchanged.
     */
    public void encrypt(KeyCrypter keyCrypter, KeyParameter aesKey) {
        checkNotNull(keyCrypter);
        checkNotNull(aesKey);

        lock.lock();
        try {
            seed = seed.encrypt(keyCrypter, aesKey);
            masterKey = masterKey.encrypt(keyCrypter, aesKey, null);

            for (WalletPocket pocket : pockets.values()) {
                pocket.encrypt(keyCrypter, aesKey);
            }
        } finally {
            lock.unlock();
        }
    }

    /* package */ void decrypt(KeyParameter aesKey) {
        checkNotNull(aesKey);

        lock.lock();
        try {
            checkState(isEncrypted());

            if (seed != null) {
                checkState(seed.isEncrypted());
                List<String> mnemonic = null;
                try {
                    mnemonic = decodeMnemonicCode(getKeyCrypter().decrypt(seed.getEncryptedData(), aesKey));
                } catch (UnreadableWalletException e) {
                    throw new RuntimeException(e);
                }
                seed = new DeterministicSeed(new byte[16], mnemonic, 0);
            }

            masterKey = masterKey.decrypt(getKeyCrypter(), aesKey);

            for (WalletPocket pocket : pockets.values()) {
                pocket.decrypt(aesKey);
            }
        } finally {
            lock.unlock();
        }
    }

    private static List<String> decodeMnemonicCode(byte[] mnemonicCode) throws UnreadableWalletException {
        try {
            return Splitter.on(" ").splitToList(new String(mnemonicCode, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new UnreadableWalletException(e.toString());
        }
    }
}