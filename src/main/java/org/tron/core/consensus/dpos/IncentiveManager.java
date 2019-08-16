package org.tron.core.consensus.dpos;

import static org.tron.core.consensus.base.Constant.WITNESS_STANDBY_LENGTH;

import com.google.protobuf.ByteString;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.consensus.ConsensusDelegate;
import org.tron.protos.Protocol.Block;

@Slf4j(topic = "consensus")
@Component
public class IncentiveManager {

  @Autowired
  private ConsensusDelegate consensusDelegate;

  public void applyBlock(Block block) {
    ByteString witness = block.getBlockHeader().getRawData().getWitnessAddress();
    AccountCapsule account = consensusDelegate.getAccountStore().getUnchecked(witness.toByteArray());
    account.setAllowance(account.getAllowance() + consensusDelegate.getWitnessPayPerBlock());
    consensusDelegate.getAccountStore().put(account.createDbKey(), account);
  }

  public void reward(List<ByteString> witnesses) {
    if (witnesses.size() > WITNESS_STANDBY_LENGTH) {
      witnesses = witnesses.subList(0, WITNESS_STANDBY_LENGTH);
    }
    long voteSum = 0;
    for (ByteString witness : witnesses) {
      voteSum += consensusDelegate.getWitnesseByAddress(witness).getVoteCount();
    }
    if (voteSum <= 0) {
      return;
    }
    long totalPay = consensusDelegate.getWitnessStandbyAllowance();
    for (ByteString witness : witnesses) {
      long pay = (long) (consensusDelegate.getWitnesseByAddress(witness).getVoteCount() * ((double) totalPay / voteSum));
      AccountCapsule accountCapsule = consensusDelegate.getAccountStore().get(witness.toByteArray());
      accountCapsule.setAllowance(accountCapsule.getAllowance() + pay);
      consensusDelegate.getAccountStore().put(accountCapsule.createDbKey(), accountCapsule);
    }
  }
}
