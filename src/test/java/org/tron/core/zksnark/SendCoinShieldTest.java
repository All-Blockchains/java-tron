package org.tron.core.zksnark;

import com.alibaba.fastjson.JSONArray;
import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.sun.jna.Pointer;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.testng.collections.Lists;
import org.tron.api.GrpcAPI;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.crypto.zksnark.ZksnarkUtils;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.ByteUtil;
import org.tron.common.utils.FileUtil;
import org.tron.common.utils.Sha256Hash;
import org.tron.common.zksnark.Librustzcash;
import org.tron.common.zksnark.LibrustzcashParam.BindingSigParams;
import org.tron.common.zksnark.LibrustzcashParam.CheckOutputParams;
import org.tron.common.zksnark.LibrustzcashParam.CheckSpendParams;
import org.tron.common.zksnark.LibrustzcashParam.ComputeCmParams;
import org.tron.common.zksnark.LibrustzcashParam.FinalCheckParams;
import org.tron.common.zksnark.LibrustzcashParam.InitZksnarkParams;
import org.tron.common.zksnark.LibrustzcashParam.IvkToPkdParams;
import org.tron.common.zksnark.LibrustzcashParam.SpendSigParams;
import org.tron.common.zksnark.ZksnarkClient;
import org.tron.core.Constant;
import org.tron.core.Wallet;
import org.tron.core.actuator.Actuator;
import org.tron.core.actuator.ActuatorFactory;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.IncrementalMerkleTreeCapsule;
import org.tron.core.capsule.PedersenHashCapsule;
import org.tron.core.capsule.ReceiveDescriptionCapsule;
import org.tron.core.capsule.SpendDescriptionCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.db.Manager;
import org.tron.core.exception.AccountResourceInsufficientException;
import org.tron.core.exception.BadItemException;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.exception.DupTransactionException;
import org.tron.core.exception.ReceiptCheckErrException;
import org.tron.core.exception.TaposException;
import org.tron.core.exception.TooBigTransactionException;
import org.tron.core.exception.TooBigTransactionResultException;
import org.tron.core.exception.TransactionExpirationException;
import org.tron.core.exception.VMIllegalException;
import org.tron.core.exception.ValidateSignatureException;
import org.tron.core.exception.ZksnarkException;
import org.tron.core.zen.ZenTransactionBuilder;
import org.tron.core.zen.ZenTransactionBuilder.SpendDescriptionInfo;
import org.tron.core.zen.address.DiversifierT;
import org.tron.core.zen.address.ExpandedSpendingKey;
import org.tron.core.zen.address.FullViewingKey;
import org.tron.core.zen.address.IncomingViewingKey;
import org.tron.core.zen.address.KeyIo;
import org.tron.core.zen.address.PaymentAddress;
import org.tron.core.zen.address.SpendingKey;
import org.tron.core.zen.merkle.IncrementalMerkleTreeContainer;
import org.tron.core.zen.merkle.IncrementalMerkleTreeContainer.EmptyMerkleRoots;
import org.tron.core.zen.merkle.IncrementalMerkleVoucherContainer;
import org.tron.core.zen.merkle.MerklePath;
import org.tron.core.zen.note.Note;
import org.tron.core.zen.note.Note.NotePlaintextEncryptionResult;
import org.tron.core.zen.note.NoteEncryption;
import org.tron.core.zen.note.NoteEncryption.Encryption;
import org.tron.core.zen.note.OutgoingPlaintext;
import org.tron.protos.Contract;
import org.tron.protos.Contract.PedersenHash;
import org.tron.protos.Contract.ReceiveDescription;
import org.tron.protos.Contract.SpendDescription;
import org.tron.protos.Protocol;
import org.tron.protos.Protocol.AccountType;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;

public class SendCoinShieldTest {

  public static final long totalBalance = 1000_0000_000_000L;
  private static String dbPath = "output_ShieldedTransaction_test";
  private static String dbDirectory = "db_ShieldedTransaction_test";
  private static String indexDirectory = "index_ShieldedTransaction_test";
  private static AnnotationConfigApplicationContext context;
  private static Manager dbManager;
  private static Wallet wallet;

  static {
    Args.setParam(
        new String[]{
            "--output-directory", dbPath,
            "--storage-db-directory", dbDirectory,
            "--storage-index-directory", indexDirectory,
            "-w",
            "--debug"
        },
        "config-test-mainnet.conf"
    );
    context = new TronApplicationContext(DefaultConfig.class);
  }

  /**
   * Init data.
   */
  @BeforeClass
  public static void init() {
    dbManager = context.getBean(Manager.class);
    wallet = context.getBean(Wallet.class);
    //init energy
    dbManager.getDynamicPropertiesStore().saveLatestBlockHeaderTimestamp(1526647838000L);
    dbManager.getDynamicPropertiesStore().saveTotalEnergyWeight(100_000L);
    dbManager.getDynamicPropertiesStore().saveLatestBlockHeaderTimestamp(0);
  }

  @AfterClass
  public static void removeDb() {
    Args.clearParam();
    context.destroy();
    FileUtil.deleteDir(new File(dbPath));
  }


  //@Test
  public void testPathMock() throws ZksnarkException {
    List<List<Boolean>> authenticationPath = Lists.newArrayList();
    Boolean[] authenticationArray = {true, false, true, false, true, false};
    for (int i = 0; i < 6; i++) {
      authenticationPath.add(Lists.newArrayList(authenticationArray));
    }
    Boolean[] indexArray = {true, false, true, false, true, false};
    List<Boolean> index = Lists.newArrayList(Arrays.asList(indexArray));
    MerklePath path = new MerklePath(authenticationPath, index);
    byte[] encode = path.encode();
    System.out.print(ByteArray.toHexString(encode));
  }

  private PedersenHash String2PedersenHash(String str) {
    PedersenHashCapsule compressCapsule1 = new PedersenHashCapsule();
    byte[] bytes1 = ByteArray.fromHexString(str);
    ZksnarkUtils.sort(bytes1);
    compressCapsule1.setContent(ByteString.copyFrom(bytes1));
    return compressCapsule1.getInstance();
  }

  private PedersenHash ByteArray2PedersenHash(byte[] bytes) {
    PedersenHashCapsule compressCapsule_in = new PedersenHashCapsule();
    compressCapsule_in.setContent(ByteString.copyFrom(bytes));
    return compressCapsule_in.getInstance();
  }

  private IncrementalMerkleVoucherContainer createComplexMerkleVoucherContainer(byte[] cm)
      throws ZksnarkException {
    IncrementalMerkleTreeContainer tree =
        new IncrementalMerkleTreeContainer(new IncrementalMerkleTreeCapsule());
    String s1 = "556f3af94225d46b1ef652abc9005dee873b2e245eef07fd5be587e0f21023b0";
    PedersenHash a = String2PedersenHash(s1);
    String s2 = "5814b127a6c6b8f07ed03f0f6e2843ff04c9851ff824a4e5b4dad5b5f3475722";
    PedersenHash b = String2PedersenHash(s2);
    String s3 = "6c030e6d7460f91668cc842ceb78cdb54470469e78cd59cf903d3a6e1aa03e7c";
    PedersenHash c = String2PedersenHash(s3);
    PedersenHash cmHash = ByteArray2PedersenHash(cm);
    tree.append(a);
    tree.append(b);
    tree.append(cmHash);
    IncrementalMerkleVoucherContainer voucher = tree.toVoucher();
    voucher.append(c);
    return voucher;
  }

  private IncrementalMerkleVoucherContainer createSimpleMerkleVoucherContainer(byte[] cm)
      throws ZksnarkException {
    IncrementalMerkleTreeContainer tree =
        new IncrementalMerkleTreeContainer(new IncrementalMerkleTreeCapsule());
    PedersenHashCapsule compressCapsule1 = new PedersenHashCapsule();
    compressCapsule1.setContent(ByteString.copyFrom(cm));
    PedersenHash a = compressCapsule1.getInstance();
    tree.append(a);
    IncrementalMerkleVoucherContainer voucher = tree.toVoucher();
    return voucher;
  }

  private String getParamsFile(String fileName) {
    return SendCoinShieldTest.class.getClassLoader()
        .getResource("params" + File.separator + fileName).getFile();
  }

  private void librustzcashInitZksnarkParams() throws ZksnarkException {
    String spendPath = getParamsFile("sapling-spend.params");
    String spendHash = "8270785a1a0d0bc77196f000ee6d221c9c9894f55307bd9357c3f0105d31ca63991ab91324160d8f53e2bbd3c2633a6eb8bdf5205d822e7f3f73edac51b2b70c";
    String outputPath = getParamsFile("sapling-output.params");
    String outputHash = "657e3d38dbb5cb5e7dd2970e8b03d69b4787dd907285b5a7f0790dcc8072f60bf593b32cc2d1c030e00ff5ae64bf84c5c3beb84ddc841d48264b4a171744d028";
    Librustzcash.librustzcashInitZksnarkParams(
        new InitZksnarkParams(spendPath.getBytes(), spendPath.length(), spendHash,
            outputPath.getBytes(), outputPath.length(), outputHash));
  }

  //@Test
  public void testStringRevert() throws Exception {
    byte[] bytes = ByteArray
        .fromHexString("6c030e6d7460f91668cc842ceb78cdb54470469e78cd59cf903d3a6e1aa03e7c");
    ZksnarkUtils.sort(bytes);
    System.out.println("testStringRevert------" + ByteArray.toHexString(bytes));
  }

  //@Test
  public void testGenerateSpendProof() throws Exception {
    librustzcashInitZksnarkParams();
    ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet);
    SpendingKey sk = SpendingKey
        .decode("ff2c06269315333a9207f817d2eca0ac555ca8f90196976324c7756504e7c9ee");
    ExpandedSpendingKey expsk = sk.expandedSpendingKey();

    DiversifierT diversifierT = new DiversifierT();
    byte[] d;
    while (true) {
      d = org.tron.keystore.Wallet.generateRandomBytes(Constant.ZC_DIVERSIFIER_SIZE);
      if (Librustzcash.librustzcashCheckDiversifier(d)) {
        break;
      }
    }
    diversifierT.setData(d);

    FullViewingKey fullViewingKey = expsk.fullViewingKey();

    IncomingViewingKey incomingViewingKey = fullViewingKey.inViewingKey();

    Optional<PaymentAddress> op = incomingViewingKey.address(diversifierT);

    Note note = new Note(op.get(), 100);
    note.rcm = ByteArray
        .fromHexString("bf4b2042e3e8c4a0b390e407a79a0b46e36eff4f7bb54b2349dbb0046ee21e02");

    IncrementalMerkleVoucherContainer voucher = createComplexMerkleVoucherContainer(note.cm());

    byte[] anchor = voucher.root().getContent().toByteArray();
    SpendDescriptionInfo spend = new SpendDescriptionInfo(expsk, note, anchor, voucher);
    Pointer ctx = Librustzcash.librustzcashSaplingProvingCtxInit();
    SpendDescriptionCapsule sdesc = builder.generateSpendProof(spend, ctx);
  }

  //@Test
  public void generateOutputProof() throws ZksnarkException {
    librustzcashInitZksnarkParams();
    ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet);
    SpendingKey spendingKey = SpendingKey.random();
    FullViewingKey fullViewingKey = spendingKey.fullViewingKey();
    IncomingViewingKey incomingViewingKey = fullViewingKey.inViewingKey();

    PaymentAddress paymentAddress = incomingViewingKey.address(new DiversifierT()).get();
    Pointer ctx = Librustzcash.librustzcashSaplingProvingCtxInit();
    builder.addOutput(fullViewingKey.getOvk(), paymentAddress, 4000, new byte[512]);
    builder.generateOutputProof(builder.getReceives().get(0), ctx);
    Librustzcash.librustzcashSaplingProvingCtxFree(ctx);
  }

  //@Test
  public void verifyOutputProof() throws ZksnarkException {
    librustzcashInitZksnarkParams();
    ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet);
    SpendingKey spendingKey = SpendingKey.random();
    FullViewingKey fullViewingKey = spendingKey.fullViewingKey();
    IncomingViewingKey incomingViewingKey = fullViewingKey.inViewingKey();

    PaymentAddress paymentAddress = incomingViewingKey.address(new DiversifierT()).get();
    Pointer ctx = Librustzcash.librustzcashSaplingProvingCtxInit();
    builder.addOutput(fullViewingKey.getOvk(), paymentAddress, 4000, new byte[512]);
    ReceiveDescriptionCapsule capsule = builder
        .generateOutputProof(builder.getReceives().get(0), ctx);
    Librustzcash.librustzcashSaplingProvingCtxFree(ctx);
    ReceiveDescription receiveDescription = capsule.getInstance();
    ctx = Librustzcash.librustzcashSaplingVerificationCtxInit();
    if (!Librustzcash.librustzcashSaplingCheckOutput(
        new CheckOutputParams(ctx,
            receiveDescription.getValueCommitment().toByteArray(),
            receiveDescription.getNoteCommitment().toByteArray(),
            receiveDescription.getEpk().toByteArray(),
            receiveDescription.getZkproof().toByteArray())
    )) {
      Librustzcash.librustzcashSaplingVerificationCtxFree(ctx);
      throw new RuntimeException("librustzcashSaplingCheckOutput error");
    }
    Librustzcash.librustzcashSaplingVerificationCtxFree(ctx);
  }


  //@Test
  public void testDecryptReceiveWithIvk() throws ZksnarkException {
    //verify c_enc
    librustzcashInitZksnarkParams();
    ZenTransactionBuilder builder = new ZenTransactionBuilder();

    SpendingKey spendingKey = SpendingKey.random();
    FullViewingKey fullViewingKey = spendingKey.fullViewingKey();
    IncomingViewingKey incomingViewingKey = fullViewingKey.inViewingKey();

    PaymentAddress paymentAddress = incomingViewingKey.address(new DiversifierT()).get();

    Pointer ctx = Librustzcash.librustzcashSaplingProvingCtxInit();
    byte[] memo = org.tron.keystore.Wallet.generateRandomBytes(512);
    builder.addOutput(fullViewingKey.getOvk(), paymentAddress, 4000, memo);

    ZenTransactionBuilder.ReceiveDescriptionInfo output = builder.getReceives().get(0);
    ReceiveDescriptionCapsule receiveDescriptionCapsule = builder.generateOutputProof(output, ctx);
    Contract.ReceiveDescription receiveDescription = receiveDescriptionCapsule.getInstance();

    Optional<Note> ret1 = Note.decrypt(
        receiveDescription.getCEnc().toByteArray(),//ciphertext
        fullViewingKey.inViewingKey().getValue(),
        receiveDescription.getEpk().toByteArray(),//epk
        receiveDescription.getNoteCommitment().toByteArray() //cm
    );

    if (ret1.isPresent()) {
      Note noteText = ret1.get();

      byte[] pk_d = new byte[32];
      if (!Librustzcash.librustzcashIvkToPkd(
          new IvkToPkdParams(incomingViewingKey.getValue(), noteText.d.getData(), pk_d))) {
        Librustzcash.librustzcashSaplingProvingCtxFree(ctx);
        return;
      }

      Assert.assertArrayEquals(paymentAddress.getPkD(), pk_d);
      Assert.assertEquals(noteText.value, 4000);
      Assert.assertArrayEquals(noteText.memo, memo);

      String paymentAddressStr = KeyIo.encodePaymentAddress(
          new PaymentAddress(noteText.d, pk_d));

      GrpcAPI.Note decrypt_note = GrpcAPI.Note.newBuilder()
          .setPaymentAddress(paymentAddressStr)
          .setValue(noteText.value)
          .setRcm(ByteString.copyFrom(noteText.rcm))
          .setMemo(ByteString.copyFrom(noteText.memo))
          .build();
    }

    Librustzcash.librustzcashSaplingProvingCtxFree(ctx);
  }

  public String byte2intstring(byte[] input){
    StringBuilder sb = new StringBuilder();
    for(byte b:input){
      sb.append(String.valueOf((int)b) + ", ");
    }
    return sb.toString();
  }

  //@Test
  public void testDecryptReceiveWithOvk() throws Exception {
    //decode c_out with ovk.
    librustzcashInitZksnarkParams();

    // construct payment address
    SpendingKey spendingKey2 = SpendingKey
        .decode("ff2c06269315333a9207f817d2eca0ac555ca8f90196976324c7756504e7c9ee");
    PaymentAddress paymentAddress2 = spendingKey2.defaultAddress();
    FullViewingKey fullViewingKey = spendingKey2.fullViewingKey();

    // generate output proof
    ZenTransactionBuilder builder2 = new ZenTransactionBuilder();
    Pointer ctx = Librustzcash.librustzcashSaplingProvingCtxInit();
    builder2.addOutput(fullViewingKey.getOvk(), paymentAddress2, 10000, new byte[512]);
    ZenTransactionBuilder.ReceiveDescriptionInfo output = builder2.getReceives().get(0);
    ReceiveDescriptionCapsule receiveDescriptionCapsule = builder2.generateOutputProof(output, ctx);
    Contract.ReceiveDescription receiveDescription = receiveDescriptionCapsule.getInstance();

    byte[] pkd = paymentAddress2.getPkD();
    Note note = new Note(paymentAddress2, 4000);//construct function：this.pkD = address.getPkD();
    byte[] memo = org.tron.keystore.Wallet.generateRandomBytes(512);
    note.memo = memo;

    byte[] cmu_opt = note.cm();
    Assert.assertNotNull(cmu_opt);

    NotePlaintextEncryptionResult enc = note.encrypt(pkd).get();
    NoteEncryption encryptor = enc.noteEncryption;
    OutgoingPlaintext out_pt = new OutgoingPlaintext(note.pkD, encryptor.esk);

    // encrypt with ovk
    Encryption.OutCiphertext outCiphertext = out_pt.encrypt(
        fullViewingKey.getOvk(),
        receiveDescription.getValueCommitment().toByteArray(),
        receiveDescription.getNoteCommitment().toByteArray(),
        encryptor);

    // get pk_d, esk from decryption of c_out with ovk
    Optional<OutgoingPlaintext> ret2 = OutgoingPlaintext.decrypt(outCiphertext,
        fullViewingKey.getOvk(),
        receiveDescription.getValueCommitment().toByteArray(),
        receiveDescription.getNoteCommitment().toByteArray(),
        encryptor.epk
    );

    if (ret2.isPresent()) {
      OutgoingPlaintext decrypted_out_ct_unwrapped = ret2.get();
      Assert.assertArrayEquals(decrypted_out_ct_unwrapped.pk_d, out_pt.pk_d);
      Assert.assertArrayEquals(decrypted_out_ct_unwrapped.esk, out_pt.esk);

      //decrypt c_enc with pkd、esk
      Encryption.EncCiphertext ciphertext = new Encryption.EncCiphertext();
      ciphertext.data = enc.encCiphertext;
      Optional<Note> foo = Note
          .decrypt(ciphertext,
              encryptor.epk,
              decrypted_out_ct_unwrapped.esk,
              decrypted_out_ct_unwrapped.pk_d,
              cmu_opt);

      if (foo.isPresent()) {
        Note bar = foo.get();
        //verify result
        Assert.assertEquals(4000, bar.value);
        Assert.assertArrayEquals(memo, bar.memo);
      } else {
        Librustzcash.librustzcashSaplingProvingCtxFree(ctx);
        Assert.assertFalse(true);
      }
    } else {
      Librustzcash.librustzcashSaplingProvingCtxFree(ctx);
      Assert.assertFalse(true);
    }

    Librustzcash.librustzcashSaplingProvingCtxFree(ctx);
  }

  //@Test
  public void pushShieldedTransactionAndDecryptWithIvk()
      throws ContractValidateException, TooBigTransactionException, TooBigTransactionResultException,
      TaposException, TransactionExpirationException, ReceiptCheckErrException,
      DupTransactionException, VMIllegalException, ValidateSignatureException, BadItemException,
      ContractExeException, AccountResourceInsufficientException, InvalidProtocolBufferException, ZksnarkException {
    Pointer ctx = Librustzcash.librustzcashSaplingProvingCtxInit();

    librustzcashInitZksnarkParams();
    dbManager.getDynamicPropertiesStore().saveAllowZksnarkTransaction(1);
    dbManager.getDynamicPropertiesStore().saveTotalShieldedPoolValue(4010 * 1000000l);
    ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet);

    // generate spend proof
    SpendingKey sk = SpendingKey
        .decode("ff2c06269315333a9207f817d2eca0ac555ca8f90196976324c7756504e7c9ee");
    ExpandedSpendingKey expsk = sk.expandedSpendingKey();
    PaymentAddress address = sk.defaultAddress();
    Note note = new Note(address, 4010 * 1000000);
    IncrementalMerkleVoucherContainer voucher = createSimpleMerkleVoucherContainer(note.cm());
    byte[] anchor = voucher.root().getContent().toByteArray();
    dbManager.getMerkleContainer()
        .putMerkleTreeIntoStore(anchor, voucher.getVoucherCapsule().getTree());
    builder.addSpend(expsk, note, anchor, voucher);

    // generate output proof
    SpendingKey spendingKey = SpendingKey.random();
    FullViewingKey fullViewingKey = spendingKey.fullViewingKey();
    IncomingViewingKey incomingViewingKey = fullViewingKey.inViewingKey();
    PaymentAddress paymentAddress = incomingViewingKey.address(new DiversifierT()).get();
    byte[] memo = org.tron.keystore.Wallet.generateRandomBytes(512);
    builder.addOutput(fullViewingKey.getOvk(), paymentAddress, 4000 * 1000000, memo);

    TransactionCapsule transactionCap = builder.build();

    boolean ok = dbManager.pushTransaction(transactionCap);
    Assert.assertTrue(ok);

    // add here
    byte[] ivk = fullViewingKey.inViewingKey().getValue();
    Protocol.Transaction t = transactionCap.getInstance();

    for (org.tron.protos.Protocol.Transaction.Contract c : t.getRawData().getContractList()) {
      if (c.getType() != ContractType.ShieldedTransferContract) {
        continue;
      }
      Contract.ShieldedTransferContract stContract = c.getParameter()
          .unpack(Contract.ShieldedTransferContract.class);
      ReceiveDescription receiveDescription = stContract.getReceiveDescription(0);

      Optional<Note> ret1 = Note.decrypt(
          receiveDescription.getCEnc().toByteArray(),//ciphertext
          ivk,
          receiveDescription.getEpk().toByteArray(),//epk
          receiveDescription.getNoteCommitment().toByteArray() //cm
      );

      if (ret1.isPresent()) {
        Note noteText = ret1.get();
        byte[] pk_d = new byte[32];
        if (!Librustzcash.librustzcashIvkToPkd(
            new IvkToPkdParams(incomingViewingKey.getValue(), noteText.d.getData(), pk_d))) {
          Librustzcash.librustzcashSaplingProvingCtxFree(ctx);
          return;
        }
        Assert.assertArrayEquals(paymentAddress.getPkD(), pk_d);
        Assert.assertEquals( 4000 * 1000000,noteText.value);
        Assert.assertArrayEquals(memo,noteText.memo);
      } else {
        Assert.assertFalse(true);
      }
    }
    // end here
    Librustzcash.librustzcashSaplingProvingCtxFree(ctx);
    Assert.assertTrue(ok);
  }

  @Test
  public void testDefaultAddress() throws ZksnarkException, BadItemException {
    IntStream.range(0, 1000).forEach( i -> {
      try {
        System.out.println(i + ": " + ByteArray.toHexString(SpendingKey.random().defaultAddress().getPkD()));
      } catch (BadItemException e) {
        e.printStackTrace();
      } catch (ZksnarkException e) {
        e.printStackTrace();
      }
    });
    PaymentAddress paymentAddress = SpendingKey.random().defaultAddress();
    Assert.assertNotEquals("0000000000000000000000000000000000000000000000000000000000000000",
        ByteArray.toHexString(paymentAddress.getPkD()));
  }

//  //@Test
  public void pushShieldedTransactionAndDecryptWithOvk()
      throws ContractValidateException, TooBigTransactionException, TooBigTransactionResultException,
      TaposException, TransactionExpirationException, ReceiptCheckErrException,
      DupTransactionException, VMIllegalException, ValidateSignatureException, BadItemException,
      ContractExeException, AccountResourceInsufficientException, InvalidProtocolBufferException, ZksnarkException {
    Pointer ctx = Librustzcash.librustzcashSaplingProvingCtxInit();

    librustzcashInitZksnarkParams();
    dbManager.getDynamicPropertiesStore().saveAllowZksnarkTransaction(1);
    dbManager.getDynamicPropertiesStore().saveTotalShieldedPoolValue(4010 * 1000000l);
    ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet);

    // generate spend proof
    SpendingKey sk = SpendingKey
        .decode("ff2c06269315333a9207f817d2eca0ac555ca8f90196976324c7756504e7c9ee");
    ExpandedSpendingKey expsk = sk.expandedSpendingKey();
    PaymentAddress address = sk.defaultAddress();
    Note note = new Note(address, 4010 * 1000000);
    IncrementalMerkleVoucherContainer voucher = createSimpleMerkleVoucherContainer(note.cm());
    byte[] anchor = voucher.root().getContent().toByteArray();
    dbManager.getMerkleContainer()
        .putMerkleTreeIntoStore(anchor, voucher.getVoucherCapsule().getTree());
    builder.addSpend(expsk, note, anchor, voucher);

    // generate output proof
    SpendingKey spendingKey = SpendingKey.random();
    FullViewingKey fullViewingKey = spendingKey.fullViewingKey();
    IncomingViewingKey incomingViewingKey = fullViewingKey.inViewingKey();
    PaymentAddress paymentAddress = incomingViewingKey.address(new DiversifierT()).get();
    byte[] memo = org.tron.keystore.Wallet.generateRandomBytes(512);
    builder.addOutput(fullViewingKey.getOvk(), paymentAddress, 4000 * 1000000, memo);

    TransactionCapsule transactionCap = builder.build();
    boolean ok = dbManager.pushTransaction(transactionCap);
    Assert.assertTrue(ok);

    // add here
    byte[] ivk = fullViewingKey.inViewingKey().getValue();
    Protocol.Transaction t = transactionCap.getInstance();
    for (org.tron.protos.Protocol.Transaction.Contract c : t.getRawData().getContractList()) {
      if (c.getType() != Protocol.Transaction.Contract.ContractType.ShieldedTransferContract) {
        continue;
      }
      Contract.ShieldedTransferContract stContract = c.getParameter()
          .unpack(Contract.ShieldedTransferContract.class);
      ReceiveDescription receiveDescription = stContract.getReceiveDescription(0);

      Encryption.OutCiphertext c_out = new Encryption.OutCiphertext();
      c_out.data = receiveDescription.getCOut().toByteArray();
      Optional<OutgoingPlaintext> notePlaintext = OutgoingPlaintext.decrypt(
          c_out,//ciphertext
          fullViewingKey.getOvk(),
          receiveDescription.getValueCommitment().toByteArray(), //cv
          receiveDescription.getNoteCommitment().toByteArray(), //cmu
          receiveDescription.getEpk().toByteArray() //epk
      );

      if (notePlaintext.isPresent()) {
        OutgoingPlaintext decrypted_out_ct_unwrapped = notePlaintext.get();
        //decode c_enc with pkd、esk
        Encryption.EncCiphertext ciphertext = new Encryption.EncCiphertext();
        ciphertext.data = receiveDescription.getCEnc().toByteArray();
        Optional<Note> foo = Note
            .decrypt(ciphertext,
                receiveDescription.getEpk().toByteArray(),
                decrypted_out_ct_unwrapped.esk,
                decrypted_out_ct_unwrapped.pk_d,
                receiveDescription.getNoteCommitment().toByteArray());

        if (foo.isPresent()) {
          Note bar = foo.get();
          //verify result
          Assert.assertEquals( 4000 * 1000000, bar.value);
          Assert.assertArrayEquals(memo, bar.memo);
        } else {
          Assert.assertFalse(true);
        }
      }
    }
    // end here

    Librustzcash.librustzcashSaplingProvingCtxFree(ctx);
    Assert.assertTrue(ok);
  }

  private byte[] getHash() {
    return Sha256Hash.of("this is a test".getBytes()).getBytes();
  }

  public void checkZksnark() throws BadItemException, ZksnarkException {
    librustzcashInitZksnarkParams();
    Pointer ctx = Librustzcash.librustzcashSaplingProvingCtxInit();
    // generate spend proof
    librustzcashInitZksnarkParams();
    dbManager.getDynamicPropertiesStore().saveAllowZksnarkTransaction(1);
    dbManager.getDynamicPropertiesStore().saveTotalShieldedPoolValue(4010 * 1000000l);
    ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet);
    SpendingKey sk = SpendingKey
        .decode("ff2c06269315333a9207f817d2eca0ac555ca8f90196976324c7756504e7c9ee");
    ExpandedSpendingKey expsk = sk.expandedSpendingKey();
    PaymentAddress address = sk.defaultAddress();
    Note note = new Note(address, 4010 * 1000000);
    IncrementalMerkleVoucherContainer voucher = createSimpleMerkleVoucherContainer(note.cm());
    byte[] anchor = voucher.root().getContent().toByteArray();
    dbManager.getMerkleContainer()
        .putMerkleTreeIntoStore(anchor, voucher.getVoucherCapsule().getTree());
    builder.addSpend(expsk, note, anchor, voucher);
    // generate output proof
    SpendingKey spendingKey = SpendingKey.random();
    FullViewingKey fullViewingKey = spendingKey.fullViewingKey();
    IncomingViewingKey incomingViewingKey = fullViewingKey.inViewingKey();
    PaymentAddress paymentAddress = incomingViewingKey.address(new DiversifierT().random()).get();
    builder
        .addOutput(fullViewingKey.getOvk(), paymentAddress, 4000 * 1000000, new byte[512]);
    TransactionCapsule transactionCap = builder.build();
    Librustzcash.librustzcashSaplingProvingCtxFree(ctx);
    boolean ret = ZksnarkClient.getInstance().CheckZksnarkProof(transactionCap.getInstance(),
        TransactionCapsule.getShieldTransactionHashIgnoreTypeException(transactionCap),
        10 * 1000000
    );
    Assert.assertTrue(ret);
  }

  //@Test
  public void testVerifySpendProof() throws BadItemException, ZksnarkException {
    librustzcashInitZksnarkParams();
    ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet);
    SpendingKey sk = SpendingKey
        .decode("ff2c06269315333a9207f817d2eca0ac555ca8f90196976324c7756504e7c9ee");
    ExpandedSpendingKey expsk = sk.expandedSpendingKey();
    PaymentAddress address = sk.defaultAddress();
    long value = 100;
    Note note = new Note(address, value);
    //    byte[] anchor = new byte[256];
    IncrementalMerkleVoucherContainer voucher = createSimpleMerkleVoucherContainer(note.cm());
    byte[] anchor = voucher.root().getContent().toByteArray();
    //    builder.addSpend(expsk, note, anchor, voucher);
    //    SpendDescriptionInfo spend = builder.getSpends().get(0);
    SpendDescriptionInfo spend = new SpendDescriptionInfo(expsk, note, anchor, voucher);
    Pointer proofContext = Librustzcash.librustzcashSaplingProvingCtxInit();
    SpendDescriptionCapsule spendDescriptionCapsule = builder
        .generateSpendProof(spend, proofContext);
    Librustzcash.librustzcashSaplingProvingCtxFree(proofContext);

    byte[] result = new byte[64];
    Librustzcash.librustzcashSaplingSpendSig(
        new SpendSigParams(expsk.getAsk(),
            spend.alpha,
            getHash(),
            result));

    Pointer verifyContext = Librustzcash.librustzcashSaplingVerificationCtxInit();
    boolean ok = Librustzcash.librustzcashSaplingCheckSpend(
        new CheckSpendParams(verifyContext,
            spendDescriptionCapsule.getValueCommitment().toByteArray(),
            spendDescriptionCapsule.getAnchor().toByteArray(),
            spendDescriptionCapsule.getNullifier().toByteArray(),
            spendDescriptionCapsule.getRk().toByteArray(),
            spendDescriptionCapsule.getZkproof().toByteArray(),
            result,
            getHash())
    );
    Librustzcash.librustzcashSaplingVerificationCtxFree(verifyContext);
    Assert.assertEquals(ok, true);
  }

  //@Test
  public void saplingBindingSig() throws BadItemException, ZksnarkException {
    Pointer ctx = Librustzcash.librustzcashSaplingProvingCtxInit();
    // generate spend proof
    librustzcashInitZksnarkParams();
    ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet);
    SpendingKey sk = SpendingKey
        .decode("ff2c06269315333a9207f817d2eca0ac555ca8f90196976324c7756504e7c9ee");
    ExpandedSpendingKey expsk = sk.expandedSpendingKey();
    PaymentAddress address = sk.defaultAddress();
    Note note = new Note(address, 4010 * 1000000);
    IncrementalMerkleVoucherContainer voucher = createSimpleMerkleVoucherContainer(note.cm());
    byte[] anchor = voucher.root().getContent().toByteArray();
    builder.addSpend(expsk, note, anchor, voucher);
    builder.generateSpendProof(builder.getSpends().get(0), ctx);
    // generate output proof
    SpendingKey spendingKey = SpendingKey.random();
    FullViewingKey fullViewingKey = spendingKey.fullViewingKey();
    IncomingViewingKey incomingViewingKey = fullViewingKey.inViewingKey();
    PaymentAddress paymentAddress = incomingViewingKey.address(new DiversifierT()).get();
    builder
        .addOutput(fullViewingKey.getOvk(), paymentAddress, 4000 * 1000000, new byte[512]);
    builder.generateOutputProof(builder.getReceives().get(0), ctx);

    // test create binding sig
    byte[] bindingSig = new byte[64];
    boolean ret = Librustzcash.librustzcashSaplingBindingSig(
        new BindingSigParams(ctx,
            builder.getValueBalance(),
            getHash(),
            bindingSig)
    );
    Librustzcash.librustzcashSaplingProvingCtxFree(ctx);
    Assert.assertTrue(ret);
  }

  //@Test
  public void pushShieldedTransaction()
      throws ContractValidateException, TooBigTransactionException, TooBigTransactionResultException,
      TaposException, TransactionExpirationException, ReceiptCheckErrException,
      DupTransactionException, VMIllegalException, ValidateSignatureException, BadItemException,
      ContractExeException, AccountResourceInsufficientException, ZksnarkException {
    Pointer ctx = Librustzcash.librustzcashSaplingProvingCtxInit();
    // generate spend proof
    librustzcashInitZksnarkParams();
    dbManager.getDynamicPropertiesStore().saveAllowZksnarkTransaction(1);
    dbManager.getDynamicPropertiesStore().saveTotalShieldedPoolValue(4010 * 1000000l);
    ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet);
    SpendingKey sk = SpendingKey
        .decode("ff2c06269315333a9207f817d2eca0ac555ca8f90196976324c7756504e7c9ee");
    ExpandedSpendingKey expsk = sk.expandedSpendingKey();
    PaymentAddress address = sk.defaultAddress();
    Note note = new Note(address, 4010 * 1000000);
    IncrementalMerkleVoucherContainer voucher = createSimpleMerkleVoucherContainer(note.cm());
    byte[] anchor = voucher.root().getContent().toByteArray();
    dbManager.getMerkleContainer()
        .putMerkleTreeIntoStore(anchor, voucher.getVoucherCapsule().getTree());
    builder.addSpend(expsk, note, anchor, voucher);
    // generate output proof
    SpendingKey spendingKey = SpendingKey.random();
    FullViewingKey fullViewingKey = spendingKey.fullViewingKey();
    IncomingViewingKey incomingViewingKey = fullViewingKey.inViewingKey();
    PaymentAddress paymentAddress = incomingViewingKey.address(new DiversifierT().random()).get();
    builder
        .addOutput(fullViewingKey.getOvk(), paymentAddress, 4000 * 1000000, new byte[512]);
    TransactionCapsule transactionCap = builder.build();
    boolean ok = dbManager.pushTransaction(transactionCap);
    Librustzcash.librustzcashSaplingProvingCtxFree(ctx);
    Assert.assertTrue(ok);
  }

  //@Test
  public void finalCheck() throws BadItemException, ZksnarkException {
    Pointer ctx = Librustzcash.librustzcashSaplingProvingCtxInit();
    librustzcashInitZksnarkParams();
    ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet);
    // generate spend proof
    SpendingKey sk = SpendingKey
        .decode("ff2c06269315333a9207f817d2eca0ac555ca8f90196976324c7756504e7c9ee");
    ExpandedSpendingKey expsk = sk.expandedSpendingKey();
    PaymentAddress address = sk.defaultAddress();
    Note note = new Note(address, 4010 * 1000000);
    IncrementalMerkleVoucherContainer voucher = createSimpleMerkleVoucherContainer(note.cm());
    byte[] anchor = voucher.root().getContent().toByteArray();
    builder.addSpend(expsk, note, anchor, voucher);
    SpendDescriptionCapsule spendDescriptionCapsule = builder
        .generateSpendProof(builder.getSpends().get(0), ctx);
    // generate output proof
    SpendingKey spendingKey = SpendingKey.random();
    FullViewingKey fullViewingKey = spendingKey.fullViewingKey();
    IncomingViewingKey incomingViewingKey = fullViewingKey.inViewingKey();
    PaymentAddress paymentAddress = incomingViewingKey.address(new DiversifierT()).get();
    builder
        .addOutput(fullViewingKey.getOvk(), paymentAddress, 4000 * 1000000, new byte[512]);
    ReceiveDescriptionCapsule receiveDescriptionCapsule = builder
        .generateOutputProof(builder.getReceives().get(0), ctx);

    //create binding sig
    byte[] bindingSig = new byte[64];
    boolean ret = Librustzcash.librustzcashSaplingBindingSig(
        new BindingSigParams(ctx,
            builder.getValueBalance(),
            getHash(),
            bindingSig)
    );
    Librustzcash.librustzcashSaplingProvingCtxFree(ctx);
    Assert.assertTrue(ret);
    // check spend
    ctx = Librustzcash.librustzcashSaplingVerificationCtxInit();
    byte[] result = new byte[64];
    Librustzcash.librustzcashSaplingSpendSig(
        new SpendSigParams(expsk.getAsk(),
            builder.getSpends().get(0).alpha,
            getHash(),
            result));

    SpendDescription spendDescription = spendDescriptionCapsule.getInstance();
    boolean ok;
    ok = Librustzcash.librustzcashSaplingCheckSpend(
        new CheckSpendParams(ctx,
            spendDescription.getValueCommitment().toByteArray(),
            spendDescription.getAnchor().toByteArray(),
            spendDescription.getNullifier().toByteArray(),
            spendDescription.getRk().toByteArray(),
            spendDescription.getZkproof().toByteArray(),
            result,
            getHash())
    );
    Assert.assertTrue(ok);

    // check output
    ReceiveDescription receiveDescription = receiveDescriptionCapsule.getInstance();
    ok = Librustzcash.librustzcashSaplingCheckOutput(
        new CheckOutputParams(ctx,
            receiveDescription.getValueCommitment().toByteArray(),
            receiveDescription.getNoteCommitment().toByteArray(),
            receiveDescription.getEpk().toByteArray(),
            receiveDescription.getZkproof().toByteArray())
    );
    Assert.assertTrue(ok);
    // final check
    ok = Librustzcash.librustzcashSaplingFinalCheck(
        new FinalCheckParams(ctx,
            builder.getValueBalance(),
            bindingSig,
            getHash())
    );
    Assert.assertTrue(ok);
    Librustzcash.librustzcashSaplingVerificationCtxFree(ctx);
  }

  //@Test
  public void testEmptyRoot() {
    byte[] bytes = IncrementalMerkleTreeContainer.emptyRoot().getContent().toByteArray();
    ZksnarkUtils.sort(bytes);
    Assert.assertEquals("3e49b5f954aa9d3545bc6c37744661eea48d7c34e3000d82b7f0010c30f4c2fb",
        ByteArray.toHexString(bytes));
  }

  //@Test
  public void testEmptyRoots() throws Exception {
    JSONArray array = readFile("merkle_roots_empty_sapling.json");
    for (int i = 0; i < 32; i++) {
      String string = array.getString(i);
      EmptyMerkleRoots emptyMerkleRootsInstance = EmptyMerkleRoots.emptyMerkleRootsInstance;
      byte[] bytes = emptyMerkleRootsInstance.emptyRoot(i).getContent().toByteArray();
      Assert.assertEquals(string, ByteArray.toHexString(bytes));
    }
  }

  private JSONArray readFile(String fileName) throws Exception {
    String file1 = SendCoinShieldTest.class.getClassLoader()
        .getResource("json" + File.separator + fileName).getFile();
    List<String> readLines = Files.readLines(new File(file1),
        Charsets.UTF_8);
    JSONArray array = JSONArray
        .parseArray(readLines.stream().reduce((s, s2) -> s + s2).get());
    return array;
  }


  //@Test
  public void testComputeCm() throws Exception {
    byte[] result = new byte[32];
    if (!Librustzcash.librustzcashComputeCm(new ComputeCmParams(
        (ByteArray.fromHexString("fc6eb90855700861de6639")),
        ByteArray
            .fromHexString("1abfbf64bc4934aaf7f29b9fea995e5a16e654e63dbe07db0ef035499d216e19"),
        9990000000L,
        ByteArray
            .fromHexString("08e3a2ff1101b628147125b786c757b483f1cf7c309f8a647055bfb1ca819c02"),
        result)
    )) {
      System.out.println(" error");
    } else {
      System.out.println(" ok");
    }
  }

  //@Test
  public void getSpendingKey() throws Exception {
    SpendingKey sk = SpendingKey
        .decode("0b862f0e70048551c08518ff49a19db027d62cdeeb2fa974db91c10e6ebcdc16");
    System.out.println(sk.encode());
    System.out.println(
        "sk.expandedSpendingKey()" + ByteUtil.toHexString(sk.expandedSpendingKey().encode()));
    System.out.println(
        "sk.fullViewKey()" + ByteUtil.toHexString(sk.fullViewingKey().encode()));
    System.out.println(
        "sk.ivk()" + ByteUtil.toHexString(sk.fullViewingKey().inViewingKey().getValue()));
    System.out.println(
        "sk.defaultDiversifier:" + ByteUtil.toHexString(sk.defaultDiversifier().getData()));

    System.out.println(
        "sk.defaultAddress:" + ByteUtil.toHexString(sk.defaultAddress().encode()));

    System.out.println("rcm:" + ByteUtil.toHexString(Note.generateR()));

    int count = 10;
    for (int i = 0; i < count; i++) {
      // new sk
      System.out.println("---- random " + i + " ----");

      sk = SpendingKey.random();
      System.out.println("sk is: " + ByteUtil.toHexString(sk.getValue()));

      DiversifierT diversifierT = new DiversifierT();
      byte[] d;
      while (true) {
        d = org.tron.keystore.Wallet.generateRandomBytes(Constant.ZC_DIVERSIFIER_SIZE);
        if (Librustzcash.librustzcashCheckDiversifier(d)) {
          break;
        }
      }
      diversifierT.setData(d);
      System.out.println("d is: " + ByteUtil.toHexString(d));

      ExpandedSpendingKey expsk = sk.expandedSpendingKey();
      System.out.println("expsk-ask is: " + ByteUtil.toHexString(expsk.getAsk()));
      System.out.println("expsk-nsk is: " + ByteUtil.toHexString(expsk.getNsk()));
      System.out.println("expsk-ovk is: " + ByteUtil.toHexString(expsk.getOvk()));

      FullViewingKey fullViewingKey = expsk.fullViewingKey();
      System.out.println("fullviewkey-ak is: " + ByteUtil.toHexString(fullViewingKey.getAk()));
      System.out.println("fullviewkey-nk is: " + ByteUtil.toHexString(fullViewingKey.getNk()));
      System.out.println("fullviewkey-ovk is: " + ByteUtil.toHexString(fullViewingKey.getOvk()));

      IncomingViewingKey incomingViewingKey = fullViewingKey.inViewingKey();
      System.out.println("ivk is: " + ByteUtil.toHexString(incomingViewingKey.getValue()));

      Optional<PaymentAddress> op = incomingViewingKey.address(diversifierT);
      System.out.println("pkD is: " + ByteUtil.toHexString(op.get().getPkD()));

      byte[] rcm = Note.generateR();
      System.out.println("rcm is " + ByteUtil.toHexString(rcm));

      byte[] alpha = Note.generateR();
      System.out.println("alpha is " + ByteUtil.toHexString(alpha));

      String address = KeyIo.encodePaymentAddress(op.get());
      System.out.println("saplingaddress is: " + address);

      // check
      PaymentAddress paymentAddress = KeyIo.decodePaymentAddress(address);
      Assert.assertEquals(ByteUtil.toHexString(paymentAddress.getD().getData()),
          ByteUtil.toHexString(d));
      Assert.assertEquals(ByteUtil.toHexString(paymentAddress.getPkD()),
          ByteUtil.toHexString(op.get().getPkD()));

    }
  }

  //@Test
  public void testTwoCMWithDiffSkInOneTx() throws Exception {
    // generate spend proof
    librustzcashInitZksnarkParams();
    dbManager.getDynamicPropertiesStore().saveAllowZksnarkTransaction(1);
    ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet);
    //prepare two cm with different sk
    SpendingKey sk1 = SpendingKey.random();
    ExpandedSpendingKey expsk1 = sk1.expandedSpendingKey();
    PaymentAddress address1 = sk1.defaultAddress();
    Note note1 = new Note(address1, 110 * 1000000);
    SpendingKey sk2 = SpendingKey.random();
    ExpandedSpendingKey expsk2 = sk2.expandedSpendingKey();
    PaymentAddress address2 = sk2.defaultAddress();
    Note note2 = new Note(address2, 100 * 1000000);
    IncrementalMerkleTreeContainer tree =
        new IncrementalMerkleTreeContainer(new IncrementalMerkleTreeCapsule());
    PedersenHashCapsule compressCapsule1 = new PedersenHashCapsule();
    compressCapsule1.setContent(ByteString.copyFrom(note1.cm()));
    PedersenHash a = compressCapsule1.getInstance();
    tree.append(a);
    IncrementalMerkleVoucherContainer voucher = tree.toVoucher();
    byte[] anchor = voucher.root().getContent().toByteArray();
    dbManager
        .getMerkleContainer()
        .putMerkleTreeIntoStore(anchor, voucher.getVoucherCapsule().getTree());

    PedersenHashCapsule compressCapsule2 = new PedersenHashCapsule();
    compressCapsule2.setContent(ByteString.copyFrom(note2.cm()));
    PedersenHash a2 = compressCapsule2.getInstance();
    tree.append(a2);
    IncrementalMerkleVoucherContainer voucher2 = tree.toVoucher();
    byte[] anchor2 = voucher2.root().getContent().toByteArray();
    dbManager
        .getMerkleContainer()
        .putMerkleTreeIntoStore(anchor2, voucher2.getVoucherCapsule().getTree());
    //add spendDesc into builder
    builder.addSpend(expsk1, note1, anchor, voucher);
    builder.addSpend(expsk2, note2, anchor2, voucher2);
    // generate output proof
    SpendingKey spendingKey = SpendingKey.random();
    FullViewingKey fullViewingKey = spendingKey.fullViewingKey();
    IncomingViewingKey incomingViewingKey = fullViewingKey.inViewingKey();
    PaymentAddress paymentAddress = incomingViewingKey.address(new DiversifierT()).get();
    builder.addOutput(fullViewingKey.getOvk(), paymentAddress, 200 * 1000000, new byte[512]);
    TransactionCapsule transactionCap = builder.build();
    //execute
    List<Actuator> actuator = ActuatorFactory.createActuator(transactionCap, dbManager);
    actuator.get(0).validate();
    TransactionResultCapsule resultCapsule = new TransactionResultCapsule();
    actuator.get(0).execute(resultCapsule);
  }

  private void executeTx(TransactionCapsule transactionCap) throws Exception {
    List<Actuator> actuator = ActuatorFactory.createActuator(transactionCap, dbManager);
    actuator.get(0).validate();
    TransactionResultCapsule resultCapsule = new TransactionResultCapsule();
    actuator.get(0).execute(resultCapsule);
  }

  // //@Test
  public void testValueBalance() throws Exception {
    librustzcashInitZksnarkParams();
    dbManager.getDynamicPropertiesStore().saveAllowZksnarkTransaction(1);
    //case 1， a public input, no input cm,  an output cm, no public output
    {
      ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet);
      String OWNER_ADDRESS =
          Wallet.getAddressPreFixString() + "548794500882809695a8a687866e76d4271a1abc";
      AccountCapsule ownerCapsule =
          new AccountCapsule(
              ByteString.copyFromUtf8("owner"),
              ByteString.copyFrom(ByteArray.fromHexString(OWNER_ADDRESS)),
              AccountType.Normal,
              110_000_000L);

      dbManager.getAccountStore().put(ownerCapsule.getAddress().toByteArray(), ownerCapsule);
      builder.setTransparentInput(ByteArray.fromHexString(OWNER_ADDRESS), 100_000_000);

      // generate output proof
      SpendingKey spendingKey = SpendingKey.random();
      FullViewingKey fullViewingKey = spendingKey.fullViewingKey();
      IncomingViewingKey incomingViewingKey = fullViewingKey.inViewingKey();
      PaymentAddress paymentAddress = incomingViewingKey.address(new DiversifierT()).get();
      builder.addOutput(fullViewingKey.getOvk(), paymentAddress, 200 * 1000000, new byte[512]);

      TransactionCapsule transactionCap = builder.build();

      // 100_000_000L + 0L !=  200_000_000L + 0L + 10_000_000L
      try {
        executeTx(transactionCap);
        Assert.fail();
      } catch (ContractValidateException e) {
        if (!e.getMessage().equals("librustzcashSaplingFinalCheck error")) {
          throw e;
        }
      }
    }

    //case 2， a public input, no input cm,  an output cm, a public output
    {
      ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet);

      String OWNER_ADDRESS =
          Wallet.getAddressPreFixString() + "548794500882809695a8a687866e76d4271a1abc";
      AccountCapsule ownerCapsule =
          new AccountCapsule(
              ByteString.copyFromUtf8("owner"),
              ByteString.copyFrom(ByteArray.fromHexString(OWNER_ADDRESS)),
              AccountType.Normal,
              110_000_000L);
      dbManager.getAccountStore().put(ownerCapsule.getAddress().toByteArray(), ownerCapsule);
      builder.setTransparentInput(ByteArray.fromHexString(OWNER_ADDRESS), 100_000_000L);

      // generate output proof
      SpendingKey spendingKey = SpendingKey.random();
      FullViewingKey fullViewingKey = spendingKey.fullViewingKey();
      IncomingViewingKey incomingViewingKey = fullViewingKey.inViewingKey();
      PaymentAddress paymentAddress = incomingViewingKey.address(new DiversifierT()).get();
      builder.addOutput(fullViewingKey.getOvk(), paymentAddress, 200 * 1000000, new byte[512]);

      String TO_ADDRESS =
          Wallet.getAddressPreFixString() + "b48794500882809695a8a687866e76d4271a1abc";
      AccountCapsule toCapsule =
          new AccountCapsule(
              ByteString.copyFromUtf8("to"),
              ByteString.copyFrom(ByteArray.fromHexString(TO_ADDRESS)),
              AccountType.Normal,
              0L);
      dbManager.getAccountStore().put(toCapsule.getAddress().toByteArray(), toCapsule);
      builder.setTransparentOutput(ByteArray.fromHexString(TO_ADDRESS), 10_000_000);

      TransactionCapsule transactionCap = builder.build();

//   100_000_000L + 0L !=  200_000_000L + 10_000_000L + 10_000_000L
      try {
        executeTx(transactionCap);
        Assert.fail();
      } catch (ContractValidateException e) {
        if (!e.getMessage().equals("librustzcashSaplingFinalCheck error")) {
          throw e;
        }
      }
    }

    //case 3， no public input, an input cm,  no output cm, a public output
    {
      ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet);

      //prepare  cm
      SpendingKey sk1 = SpendingKey.random();
      ExpandedSpendingKey expsk1 = sk1.expandedSpendingKey();
      PaymentAddress address1 = sk1.defaultAddress();
      Note note1 = new Note(address1, 110 * 1000000);

      IncrementalMerkleTreeContainer tree =
          new IncrementalMerkleTreeContainer(new IncrementalMerkleTreeCapsule());
      PedersenHashCapsule compressCapsule1 = new PedersenHashCapsule();
      compressCapsule1.setContent(ByteString.copyFrom(note1.cm()));
      PedersenHash a = compressCapsule1.getInstance();
      tree.append(a);
      IncrementalMerkleVoucherContainer voucher = tree.toVoucher();
      byte[] anchor = voucher.root().getContent().toByteArray();
      dbManager
          .getMerkleContainer()
          .putMerkleTreeIntoStore(anchor, voucher.getVoucherCapsule().getTree());

      //add spendDesc into builder
      builder.addSpend(expsk1, note1, anchor, voucher);

      String TO_ADDRESS =
          Wallet.getAddressPreFixString() + "b48794500882809695a8a687866e76d4271a1abc";
      AccountCapsule toCapsule =
          new AccountCapsule(
              ByteString.copyFromUtf8("to"),
              ByteString.copyFrom(ByteArray.fromHexString(TO_ADDRESS)),
              AccountType.Normal,
              0L);
      dbManager.getAccountStore().put(toCapsule.getAddress().toByteArray(), toCapsule);
      builder.setTransparentOutput(ByteArray.fromHexString(TO_ADDRESS), 10_000_000);

      TransactionCapsule transactionCap = builder.build();

      //   0L + 110_000_000L  !=  200_000_000L + 0L + 10_000_000L
      try {
        executeTx(transactionCap);
        Assert.fail();
      } catch (ContractValidateException e) {
        if (!e.getMessage().equals("librustzcashSaplingFinalCheck error")) {
          throw e;
        }
      }
    }

    //case 4， no public input, an input cm,  an output cm, no public output
    {
      ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet);

      //prepare  cm
      SpendingKey sk1 = SpendingKey.random();
      ExpandedSpendingKey expsk1 = sk1.expandedSpendingKey();
      PaymentAddress address1 = sk1.defaultAddress();
      Note note1 = new Note(address1, 110 * 1000000);

      IncrementalMerkleTreeContainer tree =
          new IncrementalMerkleTreeContainer(new IncrementalMerkleTreeCapsule());
      PedersenHashCapsule compressCapsule1 = new PedersenHashCapsule();
      compressCapsule1.setContent(ByteString.copyFrom(note1.cm()));
      PedersenHash a = compressCapsule1.getInstance();
      tree.append(a);
      IncrementalMerkleVoucherContainer voucher = tree.toVoucher();
      byte[] anchor = voucher.root().getContent().toByteArray();
      dbManager
          .getMerkleContainer()
          .putMerkleTreeIntoStore(anchor, voucher.getVoucherCapsule().getTree());

      //add spendDesc into builder
      builder.addSpend(expsk1, note1, anchor, voucher);

      // generate output proof
      SpendingKey spendingKey = SpendingKey.random();
      FullViewingKey fullViewingKey = spendingKey.fullViewingKey();
      IncomingViewingKey incomingViewingKey = fullViewingKey.inViewingKey();
      PaymentAddress paymentAddress = incomingViewingKey.address(new DiversifierT()).get();
      builder.addOutput(fullViewingKey.getOvk(), paymentAddress, 200 * 1000000, new byte[512]);

      TransactionCapsule transactionCap = builder.build();

      //   110_000_000L + 0L!=  200_000_000L + 0L + 10_000_000L
      try {
        executeTx(transactionCap);
        Assert.fail();
      } catch (ContractValidateException e) {
        if (!e.getMessage().equals("librustzcashSaplingFinalCheck error")) {
          throw e;
        }
      }
    }

    //case 5， no public input, an input cm,  an output cm, a public output
    {
      ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet);

      //prepare  cm
      SpendingKey sk1 = SpendingKey.random();
      ExpandedSpendingKey expsk1 = sk1.expandedSpendingKey();
      PaymentAddress address1 = sk1.defaultAddress();
      Note note1 = new Note(address1, 110 * 1000000);

      IncrementalMerkleTreeContainer tree =
          new IncrementalMerkleTreeContainer(new IncrementalMerkleTreeCapsule());
      PedersenHashCapsule compressCapsule1 = new PedersenHashCapsule();
      compressCapsule1.setContent(ByteString.copyFrom(note1.cm()));
      PedersenHash a = compressCapsule1.getInstance();
      tree.append(a);
      IncrementalMerkleVoucherContainer voucher = tree.toVoucher();
      byte[] anchor = voucher.root().getContent().toByteArray();
      dbManager
          .getMerkleContainer()
          .putMerkleTreeIntoStore(anchor, voucher.getVoucherCapsule().getTree());

      //add spendDesc into builder
      builder.addSpend(expsk1, note1, anchor, voucher);

      // generate output proof
      SpendingKey spendingKey = SpendingKey.random();
      FullViewingKey fullViewingKey = spendingKey.fullViewingKey();
      IncomingViewingKey incomingViewingKey = fullViewingKey.inViewingKey();
      PaymentAddress paymentAddress = incomingViewingKey.address(new DiversifierT()).get();
      builder.addOutput(fullViewingKey.getOvk(), paymentAddress, 200 * 1000000, new byte[512]);

      String TO_ADDRESS =
          Wallet.getAddressPreFixString() + "b48794500882809695a8a687866e76d4271a1abc";
      AccountCapsule toCapsule =
          new AccountCapsule(
              ByteString.copyFromUtf8("to"),
              ByteString.copyFrom(ByteArray.fromHexString(TO_ADDRESS)),
              AccountType.Normal,
              0L);
      dbManager.getAccountStore().put(toCapsule.getAddress().toByteArray(), toCapsule);
      builder.setTransparentOutput(ByteArray.fromHexString(TO_ADDRESS), 10_000_000);

      TransactionCapsule transactionCap = builder.build();

      //     0L + 110_000_000L !=  200_000_000L + 10_000_000L + 10_000_000L
      try {
        executeTx(transactionCap);
        Assert.fail();
      } catch (ContractValidateException e) {
        if (!e.getMessage().equals("librustzcashSaplingFinalCheck error")) {
          throw e;
        }
      }
    }
  }

  //  //@Test
  public void TestCreateMultipleTxAtTheSameTime() throws Exception {
    librustzcashInitZksnarkParams();
    dbManager.getDynamicPropertiesStore().saveAllowZksnarkTransaction(1);
    List<TransactionCapsule> txList = Lists.newArrayList();
    //case 1， a public input, no input cm,  an output cm, no public output
    {
      ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet);
      String OWNER_ADDRESS =
          Wallet.getAddressPreFixString() + "548794500882809695a8a687866e76d4271a1abc";
      AccountCapsule ownerCapsule =
          new AccountCapsule(
              ByteString.copyFromUtf8("owner"),
              ByteString.copyFrom(ByteArray.fromHexString(OWNER_ADDRESS)),
              AccountType.Normal,
              220_000_000L);

      dbManager.getAccountStore().put(ownerCapsule.getAddress().toByteArray(), ownerCapsule);
      builder.setTransparentInput(ByteArray.fromHexString(OWNER_ADDRESS), 210_000_000L);

      // generate output proof
      SpendingKey spendingKey = SpendingKey.random();
      FullViewingKey fullViewingKey = spendingKey.fullViewingKey();
      IncomingViewingKey incomingViewingKey = fullViewingKey.inViewingKey();
      PaymentAddress paymentAddress = incomingViewingKey.address(new DiversifierT()).get();
      builder.addOutput(fullViewingKey.getOvk(), paymentAddress, 200 * 1000000, new byte[512]);

      TransactionCapsule transactionCap1 = builder.build();
      transactionCap1.setBlockNum(1);
      txList.add(transactionCap1);

      // 210_000_000L + 0L =  200_000_000L + 0L + 10_000_000L
    }

    //case 2， a public input, no input cm,  an output cm, a public output
    {
      ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet);

      String OWNER_ADDRESS =
          Wallet.getAddressPreFixString() + "548794500882809695a8a687866e76d4271a1abc";
      AccountCapsule ownerCapsule =
          new AccountCapsule(
              ByteString.copyFromUtf8("owner"),
              ByteString.copyFrom(ByteArray.fromHexString(OWNER_ADDRESS)),
              AccountType.Normal,
              230_000_000L);
      dbManager.getAccountStore().put(ownerCapsule.getAddress().toByteArray(), ownerCapsule);
      builder.setTransparentInput(ByteArray.fromHexString(OWNER_ADDRESS), 220_000_000L);

      // generate output proof
      SpendingKey spendingKey = SpendingKey.random();
      FullViewingKey fullViewingKey = spendingKey.fullViewingKey();
      IncomingViewingKey incomingViewingKey = fullViewingKey.inViewingKey();
      PaymentAddress paymentAddress = incomingViewingKey.address(new DiversifierT()).get();
      builder.addOutput(fullViewingKey.getOvk(), paymentAddress, 200 * 1000000, new byte[512]);

      String TO_ADDRESS =
          Wallet.getAddressPreFixString() + "b48794500882809695a8a687866e76d4271a1abc";
      AccountCapsule toCapsule =
          new AccountCapsule(
              ByteString.copyFromUtf8("to"),
              ByteString.copyFrom(ByteArray.fromHexString(TO_ADDRESS)),
              AccountType.Normal,
              0L);
      dbManager.getAccountStore().put(toCapsule.getAddress().toByteArray(), toCapsule);
      builder.setTransparentOutput(ByteArray.fromHexString(TO_ADDRESS), 10_000_000);

      TransactionCapsule transactionCap1 = builder.build();
      transactionCap1.setBlockNum(2);
      txList.add(transactionCap1);

//   220_000_000L + 0L =  200_000_000L + 10_000_000L + 10_000_000L

    }

    //case 3， no public input, an input cm,  no output cm, a public output
    {
      ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet);

      //prepare  cm
      SpendingKey sk1 = SpendingKey.random();
      ExpandedSpendingKey expsk1 = sk1.expandedSpendingKey();
      PaymentAddress address1 = sk1.defaultAddress();
      Note note1 = new Note(address1, 20 * 1000000);

      IncrementalMerkleTreeContainer tree =
          new IncrementalMerkleTreeContainer(new IncrementalMerkleTreeCapsule());
      PedersenHashCapsule compressCapsule1 = new PedersenHashCapsule();
      compressCapsule1.setContent(ByteString.copyFrom(note1.cm()));
      PedersenHash a = compressCapsule1.getInstance();
      tree.append(a);
      IncrementalMerkleVoucherContainer voucher = tree.toVoucher();
      byte[] anchor = voucher.root().getContent().toByteArray();
      dbManager
          .getMerkleContainer()
          .putMerkleTreeIntoStore(anchor, voucher.getVoucherCapsule().getTree());

      //add spendDesc into builder
      builder.addSpend(expsk1, note1, anchor, voucher);

      String TO_ADDRESS =
          Wallet.getAddressPreFixString() + "b48794500882809695a8a687866e76d4271a1abc";
      AccountCapsule toCapsule =
          new AccountCapsule(
              ByteString.copyFromUtf8("to"),
              ByteString.copyFrom(ByteArray.fromHexString(TO_ADDRESS)),
              AccountType.Normal,
              0L);
      dbManager.getAccountStore().put(toCapsule.getAddress().toByteArray(), toCapsule);
      builder.setTransparentOutput(ByteArray.fromHexString(TO_ADDRESS), 10_000_000);

      TransactionCapsule transactionCap1 = builder.build();
      transactionCap1.setBlockNum(3);
      txList.add(transactionCap1);

//         0L + 20_000_000L  =  0L + 10_000_000L +  10_000_000L
    }

    System.out.println("TxList size:" + txList.size());
    txList.parallelStream().forEach(transactionCapsule -> {
      try {
        executeTx(transactionCapsule);
        System.out.println("Success execute tx,num:" + transactionCapsule.getBlockNum());
      } catch (Exception ex) {
        System.out.println(ex);
      }
    });
  }

  //  //@Test
  public void TestCtxGeneratesTooMuchProof() throws Exception {
    librustzcashInitZksnarkParams();
    dbManager.getDynamicPropertiesStore().saveAllowZksnarkTransaction(1);
    //case 3， no public input, an input cm,  no output cm, a public output
    {
      //prepare two cm with different sk, cm1 is used for fake spendDesc
      SpendingKey sk1 = SpendingKey.random();
      ExpandedSpendingKey expsk1 = sk1.expandedSpendingKey();
      PaymentAddress address1 = sk1.defaultAddress();
      Note note1 = new Note(address1, 110 * 1000000);

      SpendingKey sk2 = SpendingKey.random();
      ExpandedSpendingKey expsk2 = sk2.expandedSpendingKey();
      PaymentAddress address2 = sk2.defaultAddress();
      Note note2 = new Note(address2, 20 * 1000000);

      IncrementalMerkleTreeContainer tree =
          new IncrementalMerkleTreeContainer(new IncrementalMerkleTreeCapsule());
      PedersenHashCapsule compressCapsule1 = new PedersenHashCapsule();
      compressCapsule1.setContent(ByteString.copyFrom(note1.cm()));
      PedersenHash a = compressCapsule1.getInstance();
      tree.append(a);
      IncrementalMerkleVoucherContainer voucher1 = tree.toVoucher();
      byte[] anchor1 = voucher1.root().getContent().toByteArray();
      dbManager
          .getMerkleContainer()
          .putMerkleTreeIntoStore(anchor1, voucher1.getVoucherCapsule().getTree());

      PedersenHashCapsule compressCapsule2 = new PedersenHashCapsule();
      compressCapsule2.setContent(ByteString.copyFrom(note2.cm()));
      PedersenHash a2 = compressCapsule2.getInstance();
      tree.append(a2);
      IncrementalMerkleVoucherContainer voucher2 = tree.toVoucher();
      byte[] anchor2 = voucher2.root().getContent().toByteArray();
      dbManager
          .getMerkleContainer()
          .putMerkleTreeIntoStore(anchor2, voucher2.getVoucherCapsule().getTree());

      ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet) {
        @Override
        public SpendDescriptionCapsule generateSpendProof(SpendDescriptionInfo spend,
            Pointer ctx) throws ZksnarkException {

          SpendDescriptionInfo fakeSpend = new SpendDescriptionInfo(expsk1, note1, anchor1,
              voucher1);
          super.generateSpendProof(fakeSpend, ctx);
          return super.generateSpendProof(spend, ctx);
        }
      };

      //add spendDesc into builder
      builder.addSpend(expsk2, note2, anchor2, voucher2);

      String TO_ADDRESS =
          Wallet.getAddressPreFixString() + "b48794500882809695a8a687866e76d4271a1abc";
      AccountCapsule toCapsule =
          new AccountCapsule(
              ByteString.copyFromUtf8("to"),
              ByteString.copyFrom(ByteArray.fromHexString(TO_ADDRESS)),
              AccountType.Normal,
              0L);
      dbManager.getAccountStore().put(toCapsule.getAddress().toByteArray(), toCapsule);
      builder.setTransparentOutput(ByteArray.fromHexString(TO_ADDRESS), 10_000_000);

      TransactionCapsule transactionCap1 = builder.build();
      try {
        executeTx(transactionCap1);
        Assert.fail();
      } catch (ContractValidateException e) {
        if (!e.getMessage().equals("librustzcashSaplingFinalCheck error")) {
          throw e;
        }
        System.out.println("Done");
      }

//         0L + 20_000_000L  =  0L + 10_000_000L +  10_000_000L
    }
  }

  //  //@Test
  public void TestGeneratesProofWithDiffCtx() throws Exception {
    librustzcashInitZksnarkParams();
    dbManager.getDynamicPropertiesStore().saveAllowZksnarkTransaction(1);

    //case 3， no public input, an input cm,  no output cm, a public output
    {

      SpendingKey sk2 = SpendingKey.random();
      ExpandedSpendingKey expsk2 = sk2.expandedSpendingKey();
      PaymentAddress address2 = sk2.defaultAddress();
      Note note2 = new Note(address2, 20 * 1000000);

      IncrementalMerkleTreeContainer tree =
          new IncrementalMerkleTreeContainer(new IncrementalMerkleTreeCapsule());

      PedersenHashCapsule compressCapsule2 = new PedersenHashCapsule();
      compressCapsule2.setContent(ByteString.copyFrom(note2.cm()));
      PedersenHash a2 = compressCapsule2.getInstance();
      tree.append(a2);
      IncrementalMerkleVoucherContainer voucher2 = tree.toVoucher();
      byte[] anchor2 = voucher2.root().getContent().toByteArray();
      dbManager
          .getMerkleContainer()
          .putMerkleTreeIntoStore(anchor2, voucher2.getVoucherCapsule().getTree());

      ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet) {
        @Override
        public SpendDescriptionCapsule generateSpendProof(SpendDescriptionInfo spend,
            Pointer ctx) throws ZksnarkException {
          Pointer fakeCtx = Librustzcash.librustzcashSaplingProvingCtxInit();
          return super.generateSpendProof(spend, fakeCtx);
        }
      };

      //add spendDesc into builder
      builder.addSpend(expsk2, note2, anchor2, voucher2);

      String TO_ADDRESS =
          Wallet.getAddressPreFixString() + "b48794500882809695a8a687866e76d4271a1abc";
      AccountCapsule toCapsule =
          new AccountCapsule(
              ByteString.copyFromUtf8("to"),
              ByteString.copyFrom(ByteArray.fromHexString(TO_ADDRESS)),
              AccountType.Normal,
              0L);
      dbManager.getAccountStore().put(toCapsule.getAddress().toByteArray(), toCapsule);
      builder.setTransparentOutput(ByteArray.fromHexString(TO_ADDRESS), 10_000_000);

      TransactionCapsule transactionCap1 = builder.build();
      try {
        executeTx(transactionCap1);
        Assert.fail();
      } catch (ContractValidateException e) {
        if (!e.getMessage().equals("librustzcashSaplingFinalCheck error")) {
          throw e;
        }
        System.out.println("Done");
      }
//         0L + 20_000_000L  =  0L + 10_000_000L +  10_000_000L
    }
  }

  //  //@Test
  public void TestGeneratesProofWithWrongAlpha() throws Exception {
    librustzcashInitZksnarkParams();
    dbManager.getDynamicPropertiesStore().saveAllowZksnarkTransaction(1);
    //case 3， no public input, an input cm,  no output cm, a public output
    {
      SpendingKey sk2 = SpendingKey.random();
      ExpandedSpendingKey expsk2 = sk2.expandedSpendingKey();
      PaymentAddress address2 = sk2.defaultAddress();
      Note note2 = new Note(address2, 20 * 1000000);

      IncrementalMerkleTreeContainer tree =
          new IncrementalMerkleTreeContainer(new IncrementalMerkleTreeCapsule());

      PedersenHashCapsule compressCapsule2 = new PedersenHashCapsule();
      compressCapsule2.setContent(ByteString.copyFrom(note2.cm()));
      PedersenHash a2 = compressCapsule2.getInstance();
      tree.append(a2);
      IncrementalMerkleVoucherContainer voucher2 = tree.toVoucher();
      byte[] anchor2 = voucher2.root().getContent().toByteArray();

      SpendDescriptionInfo spendDescriptionInfo = new SpendDescriptionInfo(expsk2, note2, anchor2,
          voucher2);
      byte[] bytes = ByteArray
          .fromHexString("0eadb4ea6533afa906673b0101343b00a6682093ccc81082d0970e5ed6f72cbd");
      spendDescriptionInfo.alpha = bytes;

      byte[] dataToBeSigned = ByteArray.fromHexString("aaaaaaaaa");
      byte[] result = new byte[64];
      Librustzcash.librustzcashSaplingSpendSig(
          new SpendSigParams(spendDescriptionInfo.expsk.getAsk(),
              spendDescriptionInfo.alpha,
              dataToBeSigned,
              result));
    }
  }


  //  //@Test
  public void TestGeneratesProofWithWrongRcm() throws Exception {
    Pointer ctx = Librustzcash.librustzcashSaplingProvingCtxInit();
    librustzcashInitZksnarkParams();
    ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet);
    // generate spend proof
    SpendingKey sk = SpendingKey.random();
    ExpandedSpendingKey expsk = sk.expandedSpendingKey();
    PaymentAddress address = sk.defaultAddress();

    Note note = new Note(address, 4010 * 1000000);
//    note.r =  ByteArray
//        .fromHexString("0xe7db4ea6533afa906673b0101343b00a6682093ccc81082d0970e5ed6f72cb6");

    IncrementalMerkleVoucherContainer voucher = createSimpleMerkleVoucherContainer(note.cm());
    byte[] anchor = voucher.root().getContent().toByteArray();
    builder.addSpend(expsk, note, anchor, voucher);
    SpendDescriptionCapsule spendDescriptionCapsule = builder
        .generateSpendProof(builder.getSpends().get(0), ctx);

  }

  //  //@Test
  public void TestWrongAsk() throws Exception {
    librustzcashInitZksnarkParams();
    dbManager.getDynamicPropertiesStore().saveAllowZksnarkTransaction(1);

    //case 3， no public input, an input cm,  no output cm, a public output
    {
      SpendingKey sk2 = SpendingKey.random();
      ExpandedSpendingKey expsk2 = sk2.expandedSpendingKey();
      PaymentAddress address2 = sk2.defaultAddress();
      Note note2 = new Note(address2, 20 * 1000000);

      IncrementalMerkleTreeContainer tree =
          new IncrementalMerkleTreeContainer(new IncrementalMerkleTreeCapsule());

      PedersenHashCapsule compressCapsule2 = new PedersenHashCapsule();
      compressCapsule2.setContent(ByteString.copyFrom(note2.cm()));
      PedersenHash a2 = compressCapsule2.getInstance();
      tree.append(a2);
      IncrementalMerkleVoucherContainer voucher2 = tree.toVoucher();
      byte[] anchor2 = voucher2.root().getContent().toByteArray();
      dbManager
          .getMerkleContainer()
          .putMerkleTreeIntoStore(anchor2, voucher2.getVoucherCapsule().getTree());

      byte[] fakeAsk = ByteArray
          .fromHexString("0xe7db4ea6533afa906673b0101343b00a6682093ccc81082d0970e5ed6f72cb6");

      ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet) {
        @Override
        public void createSpendAuth(byte[] dataToBeSigned) throws ZksnarkException {
          for (int i = 0; i < this.getSpends().size(); i++) {
            byte[] result = new byte[64];
            Librustzcash.librustzcashSaplingSpendSig(
                new SpendSigParams(fakeAsk,
                    this.getSpends().get(i).alpha,
                    dataToBeSigned,
                    result));
            this.getContractBuilder().getSpendDescriptionBuilder(i)
                .setSpendAuthoritySignature(ByteString.copyFrom(result));
          }
        }
      };

      //add spendDesc into builder
      builder.addSpend(expsk2, note2, anchor2, voucher2);

      String TO_ADDRESS =
          Wallet.getAddressPreFixString() + "b48794500882809695a8a687866e76d4271a1abc";
      AccountCapsule toCapsule =
          new AccountCapsule(
              ByteString.copyFromUtf8("to"),
              ByteString.copyFrom(ByteArray.fromHexString(TO_ADDRESS)),
              AccountType.Normal,
              0L);
      dbManager.getAccountStore().put(toCapsule.getAddress().toByteArray(), toCapsule);
      builder.setTransparentOutput(ByteArray.fromHexString(TO_ADDRESS), 10_000_000);

      TransactionCapsule transactionCap1 = builder.build();
      try {
        executeTx(transactionCap1);
        Assert.fail();
      } catch (ContractValidateException e) {
        if (!e.getMessage().equals("librustzcashSaplingCheckSpend error")) {
          throw e;
        }
        System.out.println("Done");
      }
    }
  }

  private SpendDescriptionInfo generateDefaultSpend() throws BadItemException, ZksnarkException {
    SpendingKey sk = SpendingKey.random();
    ExpandedSpendingKey expsk = sk.expandedSpendingKey();
    PaymentAddress address = sk.defaultAddress();
    Note note = new Note(address, 20 * 1000000);

    IncrementalMerkleTreeContainer tree =
        new IncrementalMerkleTreeContainer(new IncrementalMerkleTreeCapsule());

    PedersenHashCapsule compressCapsule = new PedersenHashCapsule();
    compressCapsule.setContent(ByteString.copyFrom(note.cm()));
    PedersenHash hash = compressCapsule.getInstance();
    tree.append(hash);
    IncrementalMerkleVoucherContainer voucher = tree.toVoucher();
    byte[] anchor = voucher.root().getContent().toByteArray();
    dbManager
        .getMerkleContainer()
        .putMerkleTreeIntoStore(anchor, voucher.getVoucherCapsule().getTree());

    return new SpendDescriptionInfo(expsk, note, anchor, voucher);
  }

  private String generateDefaultToAccount() {
    String TO_ADDRESS =
        Wallet.getAddressPreFixString() + "b48794500882809695a8a687866e76d4271a1abc";
    AccountCapsule toCapsule =
        new AccountCapsule(
            ByteString.copyFromUtf8("to"),
            ByteString.copyFrom(ByteArray.fromHexString(TO_ADDRESS)),
            AccountType.Normal,
            0L);
    dbManager.getAccountStore().put(toCapsule.getAddress().toByteArray(), toCapsule);
    return TO_ADDRESS;
  }

  private TransactionCapsule generateDefaultBuilder(ZenTransactionBuilder builder)
      throws BadItemException, ZksnarkException {
    //add spendDesc into builder
    SpendDescriptionInfo spendDescriptionInfo = generateDefaultSpend();
    builder.addSpend(spendDescriptionInfo);

    //add to transparent
    String TO_ADDRESS = generateDefaultToAccount();
    builder.setTransparentOutput(ByteArray.fromHexString(TO_ADDRESS), 10_000_000);

    TransactionCapsule transactionCap = builder.build();
    return transactionCap;
  }

  //  //@Test
  public void TesDefaultBuilder() throws Exception {
    librustzcashInitZksnarkParams();
    dbManager.getDynamicPropertiesStore().saveAllowZksnarkTransaction(1);

    ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet);
    TransactionCapsule transactionCapsule = generateDefaultBuilder(builder);
    executeTx(transactionCapsule);
  }

  //  //@Test
  public void TestWrongSpendRk() throws Exception {
    librustzcashInitZksnarkParams();
    dbManager.getDynamicPropertiesStore().saveAllowZksnarkTransaction(1);

    {
      ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet) {
        //set wrong rk
        @Override
        public SpendDescriptionCapsule generateSpendProof(SpendDescriptionInfo spend,
            Pointer ctx) throws ZksnarkException {
          SpendDescriptionCapsule spendDescriptionCapsule = super.generateSpendProof(spend, ctx);
          //The format is correct, but it does not belong to this
          // note value ,fake : 200_000_000,real:20_000_000
          byte[] fakeRk = ByteArray
              .fromHexString("a167824f65f874075cf81968f9f41096c28a2d9c6396601291f76782e6bdc0a4");
          System.out.println(
              "rk:" + ByteArray.toHexString(spendDescriptionCapsule.getRk().toByteArray()));
          spendDescriptionCapsule.setRk(fakeRk);
          return spendDescriptionCapsule;
        }
      };

      TransactionCapsule transactionCapsule = generateDefaultBuilder(builder);

      try {
        executeTx(transactionCapsule);
        Assert.fail();
      } catch (ContractValidateException e) {
        if (!e.getMessage().equals("librustzcashSaplingCheckSpend error")) {
          throw e;
        }
        System.out.println("Done");
      }
    }
  }

  //  //@Test
  public void TestWrongSpendProof() throws Exception {
    librustzcashInitZksnarkParams();
    dbManager.getDynamicPropertiesStore().saveAllowZksnarkTransaction(1);

    {
      ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet) {
        //set wrong proof
        @Override
        public SpendDescriptionCapsule generateSpendProof(SpendDescriptionInfo spend,
            Pointer ctx) throws ZksnarkException {
          SpendDescriptionCapsule spendDescriptionCapsule = super.generateSpendProof(spend, ctx);
          //The format is correct, but it does not belong to this
          // note value ,fake : 200_000_000,real:20_000_000
          byte[] fakeProof = ByteArray
              .fromHexString(
                  "0ac001af7f0059cdfec9eed3900b3a4b25ace3cdeb7e962929be9432e51b222be6d7b885d5393c0d373c5b3dbc19210f94e7de831750c5d3a545bbe3732b4d87e4b4350c29519cbebdabd599db9e685f37af2440abc29b3c11cc1dc6712582f74fe06506182e9202b20467017c53fb6d744cd6e08b6428d0e0607688b67876036d2e30617fe020b1fd33ce96cda898e679f44f9715d5681ee0e42f419d7af4d438240fee7b6519e525f452d2ac56b1fb7cd12e9fb0b39caf6f84918b76fa5d4627021d");
          System.out.println("zkproof:" + ByteArray
              .toHexString(spendDescriptionCapsule.getZkproof().toByteArray()));

          spendDescriptionCapsule.setZkproof(fakeProof);
          return spendDescriptionCapsule;
        }
      };

      TransactionCapsule transactionCapsule = generateDefaultBuilder(builder);

      try {
        executeTx(transactionCapsule);
        Assert.fail();
      } catch (ContractValidateException e) {
        if (!e.getMessage().equals("librustzcashSaplingCheckSpend error")) {
          throw e;
        }
        System.out.println("Done");
      }
    }
  }

  //  //@Test
  public void TestWrongNf() throws Exception {
    librustzcashInitZksnarkParams();
    dbManager.getDynamicPropertiesStore().saveAllowZksnarkTransaction(1);

    {
      ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet) {
        //set wrong nf
        @Override
        public SpendDescriptionCapsule generateSpendProof(SpendDescriptionInfo spend,
            Pointer ctx) throws ZksnarkException {
          SpendDescriptionCapsule spendDescriptionCapsule = super.generateSpendProof(spend, ctx);

          //The format is correct, but it does not belong to this
          // note value ,fake : 200_000_000,real:20_000_000
          byte[] bytes = ByteArray
              .fromHexString(
                  "7b21b1bc8aba1bb8d5a3638ef8e3c741b84ca7c122053a1072a932c043a0a9500000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000");//256
          System.out.println(
              "nf:" + ByteArray.toHexString(spendDescriptionCapsule.getNullifier().toByteArray()));
          spendDescriptionCapsule.setNullifier(bytes);
          return spendDescriptionCapsule;
        }
      };

      TransactionCapsule transactionCapsule = generateDefaultBuilder(builder);

      try {
        executeTx(transactionCapsule);
        Assert.fail();
      } catch (ContractValidateException e) {
        if (!e.getMessage().equals("librustzcashSaplingCheckSpend error")) {
          throw e;
        }
        System.out.println("Done");
        return;
      }
    }
  }

  //  //@Test
  public void TestWrongAnchor() throws Exception {
    librustzcashInitZksnarkParams();
    dbManager.getDynamicPropertiesStore().saveAllowZksnarkTransaction(1);

    {
      ZenTransactionBuilder builder = new ZenTransactionBuilder(wallet) {
        //set wrong anchor
        @Override
        public SpendDescriptionCapsule generateSpendProof(SpendDescriptionInfo spend,
            Pointer ctx) throws ZksnarkException {
          SpendDescriptionCapsule spendDescriptionCapsule = super.generateSpendProof(spend, ctx);
          //The format is correct, but it does not belong to this
          // note value ,fake : 200_000_000,real:20_000_000
          byte[] bytes = ByteArray
              .fromHexString(
                  "bd7e296f492ffc23248b1815277b29af3a8970fff70f8256492bbea79b9a5e3e");//256
          System.out.println(
              "bytes:" + ByteArray.toHexString(spendDescriptionCapsule.getAnchor().toByteArray()));
          spendDescriptionCapsule.setAnchor(bytes);
          return spendDescriptionCapsule;
        }
      };

      TransactionCapsule transactionCapsule = generateDefaultBuilder(builder);

      try {
        executeTx(transactionCapsule);
        Assert.fail();
      } catch (ContractValidateException e) {
        if (!e.getMessage().equals("Rt is invalid.")) {
          throw e;
        }
        System.out.println("Done");
        return;
      }
    }
  }
}
