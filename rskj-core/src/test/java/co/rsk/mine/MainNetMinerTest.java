package co.rsk.mine;

import co.rsk.bitcoinj.core.NetworkParameters;
import co.rsk.bitcoinj.core.VerificationException;
import co.rsk.config.ConfigUtils;
import co.rsk.config.RskSystemProperties;
import co.rsk.core.DifficultyCalculator;
import co.rsk.core.bc.BlockChainImpl;
import co.rsk.core.bc.BlockChainImplTest;
import co.rsk.test.World;
import co.rsk.test.builders.BlockChainBuilder;
import co.rsk.validators.BlockUnclesValidationRule;
import co.rsk.validators.ProofOfWorkRule;
import org.ethereum.config.BlockchainNetConfig;
import org.ethereum.config.net.MainNetConfig;
import org.ethereum.core.Genesis;
import org.ethereum.core.ImportResult;
import org.ethereum.crypto.ECKey;
import org.ethereum.facade.EthereumImpl;
import org.ethereum.rpc.TypeConverter;
import org.ethereum.validator.DifficultyRule;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;

/**
 * Created by SerAdmin on 1/3/2018.
 */
public class MainNetMinerTest {
    private BlockchainNetConfig oldConfig;
    private BlockChainImpl blockchain;
    public static DifficultyCalculator DIFFICULTY_CALCULATOR ;


    @Before
    public void setup() {
        oldConfig =RskSystemProperties.CONFIG.getBlockchainConfig();
        RskSystemProperties.CONFIG.setBlockchainConfig(new MainNetConfig());
        DIFFICULTY_CALCULATOR = new DifficultyCalculator(RskSystemProperties.CONFIG);
        World world = new World();
        blockchain = world.getBlockChain();

    }

    @After
    public void clean() {
        RskSystemProperties.CONFIG.setBlockchainConfig(oldConfig);
    }
    /*
     * This test is probabilistic, but it has a really high chance to pass. We will generate
     * a random block that it is unlikely to pass the Long.MAX_VALUE difficulty, though
     * it may happen once. Twice would be suspicious.
     */
    @Test
    public void submitBitcoinBlockProofOfWorkNotGoodEnough() {
        /* We need a low target */
        BlockChainImpl bc = new BlockChainBuilder().build();
        Genesis gen = (Genesis) BlockChainImplTest.getGenesisBlock(bc);
        gen.getHeader().setDifficulty(BigInteger.valueOf(Long.MAX_VALUE).toByteArray());
        bc.setStatus(gen, gen.getCumulativeDifficulty());
        World world = new World(bc, gen);
        blockchain = world.getBlockChain();

        EthereumImpl ethereumImpl = Mockito.mock(EthereumImpl.class);

        BlockUnclesValidationRule unclesValidationRule = Mockito.mock(BlockUnclesValidationRule.class);
        Mockito.when(unclesValidationRule.isValid(Mockito.any())).thenReturn(true);
        MinerServer minerServer = new MinerServerImpl(ethereumImpl, this.blockchain, null, this.blockchain.getPendingState(), blockchain.getRepository(), ConfigUtils.getDefaultMiningConfig(), unclesValidationRule, world.getBlockProcessor(), DIFFICULTY_CALCULATOR,
                new GasLimitCalculator(RskSystemProperties.CONFIG),
                new ProofOfWorkRule(RskSystemProperties.CONFIG).setFallbackMiningEnabled(false));

        minerServer.start();
        MinerWork work = minerServer.getWork();

        co.rsk.bitcoinj.core.BtcBlock bitcoinMergedMiningBlock = getMergedMiningBlock(work);

        bitcoinMergedMiningBlock.setNonce(2);

        SubmitBlockResult result = minerServer.submitBitcoinBlock(work.getBlockHashForMergedMining(), bitcoinMergedMiningBlock);

        Assert.assertEquals("ERROR", result.getStatus());
        Assert.assertNull(result.getBlockInfo());

        Mockito.verify(ethereumImpl, Mockito.times(0)).addNewMinedBlock(Mockito.any());
    }

    //throws IOException, FileNotFoundException
    public static void saveToFile(byte[] array, String OUTPUT_FILE_NAME) {
        try {
            FileOutputStream fos = new FileOutputStream(OUTPUT_FILE_NAME);
            fos.write(array);
            fos.close();
        } catch (IOException e) {
            System.out.println("Something is wrong when writing to file "+OUTPUT_FILE_NAME+". Aborting");
            System.exit(-1);
        }
    }
    @Test
    public void generateFallbackMinedBlock() throws InterruptedException {
        // generate private keys for testing now.
        ECKey privateMiningKey0 = ECKey.fromPrivate(BigInteger.TEN);
        ECKey privateMiningKey1 = ECKey.fromPrivate(BigInteger.TEN.add(BigInteger.ONE));
        byte[] privKey0 = privateMiningKey0.getPrivKeyBytes();
        saveToFile(privKey0,"privkey0.bin");
        byte[] privKey1 = privateMiningKey1.getPrivKeyBytes();
        saveToFile(privKey1,"privkey1.bin");


        EthereumImpl ethereumImpl = Mockito.mock(EthereumImpl.class);
        Mockito.when(ethereumImpl.addNewMinedBlock(Mockito.any())).thenReturn(ImportResult.IMPORTED_BEST);

        BlockUnclesValidationRule unclesValidationRule = Mockito.mock(BlockUnclesValidationRule.class);
        Mockito.when(unclesValidationRule.isValid(Mockito.any())).thenReturn(true);
        MinerServer minerServer = new MinerServerImpl(ethereumImpl, blockchain, null,
                blockchain.getPendingState(), blockchain.getRepository(), ConfigUtils.getDefaultMiningConfig(),
                unclesValidationRule, null, DIFFICULTY_CALCULATOR,
                new GasLimitCalculator(RskSystemProperties.CONFIG),
                new ProofOfWorkRule(RskSystemProperties.CONFIG).setFallbackMiningEnabled(true));

        minerServer.setFallbackMining(true);
        minerServer.start();

        // Blocks are generated auomatically
        // but we can call minerServer.generateFallbackBlock() to generate it manually
        // boolean result = minerServer.generateFallbackBlock();
        // Assert.assertTrue(result);

        while (((MinerServerImpl)minerServer).getFallbackBlocksGenerated()==0) {
            Thread.sleep(1000); //
        }

        Mockito.verify(ethereumImpl, Mockito.times(1)).addNewMinedBlock(Mockito.any());
        // mine another
        // NOTE that is NOT using the next block (parity change) because of the blockchain mockito
        // to mine a subsequent block, use a real blockchain, not the mockito.
        minerServer.buildBlockToMine(blockchain.getBestBlock(), false);

        //result = minerServer.generateFallbackBlock();
        //Assert.assertTrue(result);
        while (((MinerServerImpl)minerServer).getFallbackBlocksGenerated()==1) {
            Thread.sleep(1000); //
        }

        Mockito.verify(ethereumImpl, Mockito.times(2)).addNewMinedBlock(Mockito.any());

    }
    /*
     * This test is much more likely to fail than the
     * submitBitcoinBlockProofOfWorkNotGoodEnough test. Even then
     * it should almost never fail.
     */
    @Test
    public void submitBitcoinBlockInvalidBlockDoesntEliminateCache() {
        /* We need a low, but not too low, target */
        BlockChainImpl bc = new BlockChainBuilder().build();
        Genesis gen = (Genesis) BlockChainImplTest.getGenesisBlock(bc);
        gen.getHeader().setDifficulty(BigInteger.valueOf(300000).toByteArray());
        bc.setStatus(gen, gen.getCumulativeDifficulty());
        World world = new World(bc, gen);
        blockchain = world.getBlockChain();

        EthereumImpl ethereumImpl = Mockito.mock(EthereumImpl.class);
        Mockito.when(ethereumImpl.addNewMinedBlock(Mockito.any())).thenReturn(ImportResult.IMPORTED_BEST);

        BlockUnclesValidationRule unclesValidationRule = Mockito.mock(BlockUnclesValidationRule.class);
        Mockito.when(unclesValidationRule.isValid(Mockito.any())).thenReturn(true);
        MinerServer minerServer = new MinerServerImpl(ethereumImpl, this.blockchain, null,
                this.blockchain.getPendingState(), blockchain.getRepository(),
                ConfigUtils.getDefaultMiningConfig(), unclesValidationRule,
                world.getBlockProcessor(), DIFFICULTY_CALCULATOR,
                new GasLimitCalculator(RskSystemProperties.CONFIG),
                new ProofOfWorkRule(RskSystemProperties.CONFIG).setFallbackMiningEnabled(false));

        minerServer.start();
        MinerWork work = minerServer.getWork();

        co.rsk.bitcoinj.core.BtcBlock bitcoinMergedMiningBlock = getMergedMiningBlock(work);

        bitcoinMergedMiningBlock.setNonce(1);

        // Try to submit a block with invalid PoW, this should not eliminate the block from the cache
        SubmitBlockResult result1 = minerServer.submitBitcoinBlock(work.getBlockHashForMergedMining(), bitcoinMergedMiningBlock);

        Assert.assertEquals("ERROR", result1.getStatus());
        Assert.assertNull(result1.getBlockInfo());
        Mockito.verify(ethereumImpl, Mockito.times(0)).addNewMinedBlock(Mockito.any());

        // Now try to submit the same block, this should work fine since the block remains in the cache
        findNonce(work, bitcoinMergedMiningBlock);

        SubmitBlockResult result2 = minerServer.submitBitcoinBlock(work.getBlockHashForMergedMining(), bitcoinMergedMiningBlock);

        Assert.assertEquals("OK", result2.getStatus());
        Assert.assertNotNull(result2.getBlockInfo());
        Mockito.verify(ethereumImpl, Mockito.times(1)).addNewMinedBlock(Mockito.any());

        // Finally, submit the same block again and validate that addNewMinedBlock is called again
        SubmitBlockResult result3 = minerServer.submitBitcoinBlock(work.getBlockHashForMergedMining(), bitcoinMergedMiningBlock);

        Assert.assertEquals("OK", result3.getStatus());
        Assert.assertNotNull(result3.getBlockInfo());
        Mockito.verify(ethereumImpl, Mockito.times(2)).addNewMinedBlock(Mockito.any());
    }

    private void findNonce(MinerWork work, co.rsk.bitcoinj.core.BtcBlock bitcoinMergedMiningBlock) {
        BigInteger target = new BigInteger(TypeConverter.stringHexToByteArray(work.getTarget()));

        while (true) {
            try {
                // Is our proof of work valid yet?
                BigInteger blockHashBI = bitcoinMergedMiningBlock.getHash().toBigInteger();
                if (blockHashBI.compareTo(target) <= 0) {
                    break;
                }
                // No, so increment the nonce and try again.
                bitcoinMergedMiningBlock.setNonce(bitcoinMergedMiningBlock.getNonce() + 1);
            } catch (VerificationException e) {
                throw new RuntimeException(e); // Cannot happen.
            }
        }
    }
    private co.rsk.bitcoinj.core.BtcBlock getMergedMiningBlock(MinerWork work) {
        NetworkParameters bitcoinNetworkParameters = co.rsk.bitcoinj.params.RegTestParams.get();
        co.rsk.bitcoinj.core.BtcTransaction bitcoinMergedMiningCoinbaseTransaction = MinerUtils.getBitcoinMergedMiningCoinbaseTransaction(bitcoinNetworkParameters, work);
        return MinerUtils.getBitcoinMergedMiningBlock(bitcoinNetworkParameters, bitcoinMergedMiningCoinbaseTransaction);
    }
}