//
// Created by rui zhou on 2020-05-15.
//

#ifndef ORDERBOOK_ORDER_BOOK_H
#define ORDERBOOK_ORDER_BOOK_H

#include <vector>
#include "orderbook_types.h"
#include "message_parser.h"


namespace ob{
    // order book definition
    class OrderBook {
        friend class ObTester;
    private:
        AllOrders_t  allOrders_;
        ProductPriceMap_t productPriceBooks_;  //buyBook_ and sellBook_

        TradeSize       recentTradeSize_;
        Price           recentTradePrice_;
        ProductId       recentTradeProduct_;

    private:
        bool isCrossAskBidPx(ProductId pid);

    public:
        OrderBook();

        PriceOrderBook_t& getBuyOrderBook(ProductId pid) {
            return productPriceBooks_[pid].first;
        }

        PriceOrderBook_t& getSellOrderBook(ProductId pid) {
            return productPriceBooks_[pid].second;
        }

#ifdef UNITTEST
        // unit test need to reset the orderbook
        void reset() {
            allOrders_.clear();
            productPriceBooks_.clear();
        }

#endif

        void doOrderMatch(std::vector<std::string> &tradeVec,std::vector<std::string> &orderVec);

        PriceOrderBook_t& getPriceOrderBook(ProductId pid,  Side side);

        void handleOrder(const Order &order);

        void handleTrade(const Trade &trade);

        void printOrderBook(std::ostream &os) const;

        void add(const Order &order);

        void remove(Order &oldOrder, const Order &newOrder);

        void modify(Order &oldOrder, Order &newOrder);
    };
}
#endif //ORDERBOOK_ORDER_BOOK_H

