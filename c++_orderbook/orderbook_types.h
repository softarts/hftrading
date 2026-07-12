//
// Created by rui zhou on 2020-05-15.
//

#ifndef ORDERBOOK_ORDERBOOK_TYPES_H
#define ORDERBOOK_ORDERBOOK_TYPES_H

#include <list>
#include <unordered_map>
#include <map>


namespace ob{
    typedef unsigned int Price;
    typedef unsigned int TradeSize;
    typedef uint64_t OrderId;
    typedef int32_t  ProductId;
    typedef unsigned int Side;


    enum class ParseResult {
        Good,
        CorruptMessage,
        InvalidMsgData,
        InvalidProductId
    };

    // ===========================================================
    // message parser type
    enum class MessageType {
        Unknown,
        Add,
        Remove,
        Modify,
        Trade
    };

    const Side BUY = 0;
    const Side SELL = 1;


    // ===========================================================
    // price info
    class PriceInfo {
    public:
        PriceInfo(size_t sz, OrderId oid):size(sz), orderId(oid){}
        size_t      size;
        OrderId     orderId;
    };


    class PriceInfoList :public std::list<PriceInfo> {
    private:
        TradeSize totalTradeSize_;
    public:
        uint32_t getTotalTradeSize() const{
            return totalTradeSize_;
        }
        void changeTradeSize(TradeSize sz) {
            totalTradeSize_+=sz;
        }
    };


    typedef PriceInfoList::iterator PriceInfoIter_t;

    // ===========================================================
    // Trade detail
    typedef struct {
        ProductId   productId;
        TradeSize   tradeSize;
        Price       tradePrice;
    }Trade;


    enum class OrderStatus {
        Normal,                 // initial status
        TradeDeleted,           // deleted via trade match
    };



    // order definition
    class Order {
    public:
        MessageType     action;     // 4
        ProductId       productId;  // 4
        Price           price;      // 4
        TradeSize       size;       // 4
        OrderId         orderId;    // 8
        Side            side;       // 4
        PriceInfoIter_t    ptr;       // 8 (iterator)
        OrderStatus     status;     // 4

        void TradeDelete() {
            status = OrderStatus ::TradeDeleted;
            // ptr will be invalid!!
        }
    };

    // ===========================================================
    // price info list
    typedef std::map<Price, PriceInfoList> PriceOrderBook_t;
    typedef std::unordered_map<OrderId, Order> AllOrders_t;
    typedef std::unordered_map<ProductId, std::pair<PriceOrderBook_t,PriceOrderBook_t>> ProductPriceMap_t;

}
#endif //ORDERBOOK_ORDERBOOK_TYPES_H
