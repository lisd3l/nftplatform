package com.fingerchar.core.manager;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fingerchar.core.base.service.IBaseService;
import com.fingerchar.core.constant.AuctionBidsStatus;
import com.fingerchar.core.constant.CommonStatus;
import com.fingerchar.core.constant.NoticeType;
import com.fingerchar.db.base.BaseEntity;
import com.fingerchar.db.domain.FcAuctionBids;
import com.fingerchar.db.domain.FcAuctionOrder;
import com.fingerchar.db.domain.FcPayToken;
import com.fingerchar.db.dto.AuctionBuyLog;
import com.fingerchar.db.dto.AuctionCancelLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/**
 * @Author： Zjm
 * @Date：2022/3/22 1:25
 */
@Service
public class FcAuctionBidsManager {

    @Autowired
    IBaseService baseService;

    @Autowired
    FcPayTokenManager payTokenManager;

    @Autowired
    FcNoticeManager noticeManager;

    public Integer finishAuctionBids(AuctionBuyLog log, FcAuctionOrder order){
        List<FcAuctionBids> auctionBidsList = this.getAuctionBids(order);
        if(auctionBidsList.isEmpty()){
            return 0;
        }

        FcAuctionBids maxAuctionBids = this.getMaxAuctionBids(auctionBidsList);
        maxAuctionBids.setStatus(AuctionBidsStatus.getCode("Sync"));
        this.update(maxAuctionBids);


        BigDecimal bonus = new BigDecimal(order.getBuying()).multiply(new BigDecimal(order.getEncourage())).divide(new BigDecimal(10000));
        FcPayToken payToken = this.payTokenManager.get(order.getBuyerToken());
        if (null != payToken) {
            bonus = bonus.divide(BigDecimal.TEN.pow(payToken.getDecimals()), 18, BigDecimal.ROUND_HALF_UP);
        }
        int len = 0;
        if (auctionBidsList.size() > 100) {
            len = 100;
        } else if (auctionBidsList.size() == 100) {
            len = 99;
        } else {
            len = auctionBidsList.size() - 1;
        }
        if (len > 0) {
            bonus = bonus.divide(BigDecimal.valueOf(len),18, BigDecimal.ROUND_HALF_UP);
        }
        FcAuctionBids bid = null;

        if(bonus.toBigInteger().compareTo(new BigInteger("0")) > 0){
            String orderJson = JSON.toJSONString(order);
            Map<String, Object> contentJson = new HashMap<>(JSON.parseObject(orderJson));
            contentJson.put("txHash", log.getTxHash());
            contentJson.put("bonus", bonus);
            String content = JSON.toJSONString(contentJson);

            Integer noticeType = NoticeType.TRADE.getType();
            Integer type = CommonStatus.AUCTION_BONUS.getType();
            for (int i = 0; i < len; i++) {
                // 处理参与者状态，并通知奖励信息
                bid = auctionBidsList.get(i);
                bid.setBonus(bonus.toString());
                bid.setStatus(AuctionBidsStatus.getCode("Sync"));
                if(bonus.compareTo(new BigDecimal(0)) > 0) {
                    this.noticeManager.add(content, bid.getOwner(), type, noticeType, order.getOwner());
                }
                this.baseService.update(bid);
            }
        }

        UpdateWrapper<FcAuctionBids> wrapper = new UpdateWrapper<>();
        wrapper.eq(FcAuctionBids.ORDER_ID, order.getId())
                .eq(FcAuctionBids.STATUS, 0)
                .eq(FcAuctionBids.DELETED, false);
        wrapper.set(FcAuctionBids.STATUS, 2);
        return this.baseService.updateByCondition(FcAuctionBids.class, wrapper);
    }

    public Integer cancelAuctionBids(AuctionCancelLog log, FcAuctionOrder order){
        List<FcAuctionBids> auctionBidsList = this.getAuctionBids(order);
        if(auctionBidsList.isEmpty()){
            return 0;
        }
        auctionBidsList = this.removeDuplicateUser(auctionBidsList);
        String content = JSON.toJSONString(order);

        Integer noticeType = NoticeType.TRADE.getType();
        Integer type = CommonStatus.CANCEL_AUCTION.getType();
        for(FcAuctionBids auctionBids: auctionBidsList){
            this.noticeManager.add(content, auctionBids.getOwner(), type, noticeType, order.getOwner());
        }
        UpdateWrapper<FcAuctionBids> wrapper = new UpdateWrapper<>();
        wrapper.eq(FcAuctionBids.ORDER_ID, order.getId())
                .eq(FcAuctionBids.STATUS, 0)
                .eq(FcAuctionBids.DELETED, false);
        wrapper.set(FcAuctionBids.STATUS, 1);
        return this.baseService.updateByCondition(FcAuctionBids.class, wrapper);
    }


    public List<FcAuctionBids> getAuctionBids(FcAuctionOrder order){
        QueryWrapper<FcAuctionBids> wrapper = new QueryWrapper<>();
        wrapper.eq(FcAuctionBids.ORDER_ID, order.getId())
                .eq(FcAuctionBids.STATUS, 0)
                .eq(BaseEntity.DELETED, false);
        wrapper.orderByDesc(BaseEntity.CREATE_TIME);
        return this.baseService.findByCondition(FcAuctionBids.class, wrapper);
    }


    public FcAuctionBids getMaxAuctionBids(List<FcAuctionBids> list){
        FcAuctionBids max = null;
        for (FcAuctionBids bid : list) {
            if(null == max ||
                    new BigDecimal(max.getBuying()).compareTo(new BigDecimal(bid.getBuying())) <= 0
            ){
                max = bid;
            }
        }
        return max;
    }

    private List<FcAuctionBids> removeDuplicateUser(List<FcAuctionBids> auctionBidsList){
        HashSet<String> set = new HashSet(auctionBidsList.size());
        List<FcAuctionBids> _list = new ArrayList<>(auctionBidsList.size()) ;
        for(FcAuctionBids auctionBids: auctionBidsList){
            if(set.add(auctionBids.getOwner())){
                _list.add(auctionBids);
            }
        }
        return _list;
    }

    public FcAuctionBids getHighestBid(Long orderId){
        QueryWrapper<FcAuctionBids> wrapper = new QueryWrapper<>();
        wrapper.eq(FcAuctionBids.ORDER_ID, orderId)
                .eq(FcAuctionBids.STATUS, 0)
                .eq(FcAuctionBids.DELETED, false)
                        .last("limit 1");
        wrapper.orderByDesc(FcAuctionBids.BUYING);
        List<FcAuctionBids> bidsList = this.baseService.findByCondition(FcAuctionBids.class, wrapper);
        if(bidsList.isEmpty()){
            return null;
        }
        return bidsList.get(0);
    }

    public Integer bid(FcAuctionBids bid){
        return this.save(bid);
    }

    public Integer update(FcAuctionBids bids){
        return this.baseService.update(bids);
    }

    public Integer save(FcAuctionBids bids){
        return this.baseService.save(bids);
    }
}
