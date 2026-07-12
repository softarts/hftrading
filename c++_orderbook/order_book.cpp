//
// Created by rui zhou on 2020-05-15.
//

#include <sstream>
#include "order_book.h"
#include "error_monitor.h"

using namespace std;

namespace ob {
    //------------------------------------------------------------
    // ctor
    OrderBook::OrderBook(){}


    //------------------------------------------------------------
    // handle order entrypoint
    void OrderBook::handleOrder(const Order &order) {
        if (order.action == MessageType::Add && isCrossAskBidPx(order.productId)) {
            ErrorMonitor::getInstance().crossBidAsk();
        }

        auto &&oldOrderIt = allOrders_.find(order.orderId);

        if (oldOrderIt == allOrders_.end()) {
            if (order.action == MessageType ::Add) {
                add(order);
            } else {
                // missing order for modify & remove
                ErrorMonitor::getInstance().modifyRemoveMissingOrder();
            }
        } else {
            // order already exist
            Order &oldOrder = oldOrderIt->second;

            if (order.action == MessageType::Add) {
                // duplicate add
                ErrorMonitor::getInstance().duplicateAdd();
            } else if (order.action == MessageType ::Remove) {
                // if there is already bid/ask price cross, the orderbook status might be corrupted
                if (isCrossAskBidPx(order.productId)) {
                    ErrorMonitor::getInstance().crossBidAsk();
                }
                remove(oldOrder, order);
            } else if (order.action == MessageType ::Modify) {
                if (isCrossAskBidPx(order.productId)) {
                    ErrorMonitor::getInstance().crossBidAsk();
                }

                //skip same price, same side and same size
                if (oldOrder.size == order.size &&
                    oldOrder.price == order.price &&
                    oldOrder.side == order.side) {
                    // this is either modify order but without any change or
                    // after trade happened, an exchange message to reflect the order book/hisentry
                    // change(which was done in HandleTrade)
                    ErrorMonitor::getInstance().modifyIgnored();
                } else {
                    modify(oldOrder, const_cast<Order&>(order));
                }
            } else {
                ErrorMonitor::getInstance().corruptMessage();
            }
        }
    }


    //------------------------------------------------------------
    // check if No trade occur when ask/price cross
    bool OrderBook::isCrossAskBidPx(ProductId pid) {
        if (productPriceBooks_.count(pid)) {
            auto &buyOrderBook = getBuyOrderBook(pid);
            auto &sellOrderBook =  getSellOrderBook(pid);
            if (buyOrderBook.empty() || sellOrderBook.empty()) {
                return false;
            }
            if (sellOrderBook.begin()->first <= buyOrderBook.rbegin()->first) {
                return true;
            }
        }
        return false;
    }

    //------------------------------------------------------------
    // use this function to generate trade(X), remove(R), modify(M)
    // message when used with msg generator
    // it doesn't modify anything
    void OrderBook::doOrderMatch(vector<string> &tradeVec,vector<string> &orderVec) {
        for (auto iter:productPriceBooks_) {
            auto pid = iter.first;

            // check only once, generate trade message in unit test
            if (isCrossAskBidPx(pid)) {
                auto &buyOrderBook = getBuyOrderBook(pid);
                auto &sellOrderBook = getSellOrderBook(pid);

                auto highBuyPrice = buyOrderBook.rbegin()->first;
                auto lowSellPrice = sellOrderBook.begin()->first;

                if (lowSellPrice <= highBuyPrice) {
                    const auto &sit = sellOrderBook.begin();
                    auto sellOrder = sit->second.begin();

                    const auto &bit = buyOrderBook.rbegin();
                    auto buyOrder = bit->second.begin();

                    auto tsz = min(sellOrder->size,buyOrder->size);
//                    auto ssz = sit->second.getTotalTradeSize();
//                    auto bsz = bit->second.getTotalTradeSize();

                    // generate trade message
                    string s0 = "X," + to_string(pid) +"," +to_string(tsz) + "," + to_string(lowSellPrice/100);
                    tradeVec.emplace_back(s0);

                    // modify order message

                    if (sellOrder->size == tsz) {
                        orderVec.emplace_back("R," + to_string(sellOrder->orderId) + ",S," + to_string(tsz) + "," +
                                              to_string(lowSellPrice / 100));
                    } else if (sellOrder->size > tsz) {
                        orderVec.emplace_back(
                                "M," + to_string(sellOrder->orderId) + ",S," + to_string(sellOrder->size - tsz) +
                                "," + to_string(lowSellPrice / 100));
                    }


                    if (buyOrder->size == tsz) {
                        orderVec.emplace_back("R," + to_string(buyOrder->orderId) + ",B," + to_string(tsz) + "," +
                                              to_string(highBuyPrice / 100));
                    } else if (buyOrder->size > tsz) {
                        orderVec.emplace_back(
                                "M," + to_string(buyOrder->orderId) + ",B," + to_string(buyOrder->size - tsz) +
                                "," + to_string(highBuyPrice / 100));
                    }

                }

            }
        }
    }

    //------------------------------------------------------------
    // add order to books
    void OrderBook::add(const Order &tmpOrder) {
        auto &&ret = allOrders_.insert(std::make_pair(tmpOrder.orderId, tmpOrder));
        Order &order = ret.first->second;

        // create priceorderbook if it isn't exist
        if (!productPriceBooks_.count(tmpOrder.productId)) {
            productPriceBooks_.emplace(tmpOrder.productId,make_pair(PriceOrderBook_t(),PriceOrderBook_t()));
        }

        // get the buy book or sell book
        PriceOrderBook_t &map =
                order.side == BUY ?getBuyOrderBook(tmpOrder.productId): getSellOrderBook(tmpOrder.productId);


        auto &&iter = map.find(order.price);

        if (iter!=map.end()) {
            iter->second.emplace_back(order.size, order.orderId);
            iter->second.changeTradeSize(order.size);
            order.ptr = iter->second.end();
            order.ptr--;
        } else {
            auto &&ret = map.insert(std::make_pair(order.price, PriceInfoList()));
            ret.first->second.emplace_back(order.size, order.orderId);
            ret.first->second.changeTradeSize(order.size);
            order.ptr = ret.first->second.end();
            order.ptr--;
        }
    }


    //------------------------------------------------------------
    // remove order
    void OrderBook::remove(Order &oldOrder, const Order &newOrder) {
        // to remove the existing order, their price, size, side must be equal.
        if (oldOrder.status != OrderStatus::TradeDeleted) {
            if (oldOrder.price != newOrder.price ||
                oldOrder.size != newOrder.size ||
                oldOrder.side != newOrder.side) {
                ErrorMonitor::getInstance().removeWithWrongData();
                return;
            }
        }

        // if the order status is 'trade delete' means the order was mark 'deleted' in handletrade
        // we don't need to modify order book again
        if (oldOrder.status != OrderStatus::TradeDeleted) {
            PriceOrderBook_t &map =
                    oldOrder.side == BUY ?getBuyOrderBook(oldOrder.productId): getSellOrderBook(oldOrder.productId);

            auto &&iter = map.find(oldOrder.price);

            if (iter == map.end()) {
                ErrorMonitor::getInstance().removeWithWrongData();
            } else {
                auto &&orderList = iter->second;
                orderList.erase(oldOrder.ptr);
                // change order list total size
                orderList.changeTradeSize(-oldOrder.size);

                if (orderList.empty()) {
                    map.erase(iter);
                }
            }
        }

        // everything is done, now time to remove it from allOrders_
        auto &&oldOrderIt = allOrders_.find(oldOrder.orderId);
        if (oldOrderIt != allOrders_.end()) {
            allOrders_.erase(oldOrderIt);
        }
    }

    //------------------------------------------------------------
    // modify order book
    void OrderBook::modify(Order &oldOrder, Order &newOrder) {
        if (oldOrder.status == OrderStatus::TradeDeleted) {
            // the order might be marked 'delete' by the trade msg generated by internal match engine,
            // but the following corresponding order is trying to modify it.
            // TODO merging order message
            ErrorMonitor::getInstance().modifyOnDeletedOrder();
            return;
        }

        // allow to change side, price
        if (newOrder.side != oldOrder.side || newOrder.price != oldOrder.price) {
            // remove old order
            remove(oldOrder, oldOrder);
            add(newOrder);
        } else {
            // same side, same price, diff size, keep time priority
            if (newOrder.side == BUY) {
                auto &&buyOrderBook = getBuyOrderBook(oldOrder.productId);
                auto &&buyOrderList = buyOrderBook.find(oldOrder.price);

                if (buyOrderList != buyOrderBook.end()) {
                    oldOrder.ptr->size = newOrder.size;
                } else {
                    ErrorMonitor::getInstance().modifyOnDeletedOrder();
                    return;
                }
            } else if (newOrder.side == SELL) {
                auto &&sellOrderBook = getSellOrderBook(oldOrder.productId);
                auto &&sellOrderList = sellOrderBook.find(oldOrder.price);

                if (sellOrderList != sellOrderBook.end()) {
                    oldOrder.ptr->size = newOrder.size;
                } else {
                    ErrorMonitor::getInstance().modifyOnDeletedOrder();
                    return;
                }
            }
        }
    }




    //------------------------------------------------------------
    // handle trade
    void OrderBook::handleTrade(const Trade &trade) {
        // ensure there is a bid/ask price cross, otherwise shouldn't have trade
        if (!isCrossAskBidPx(trade.productId)) {
            ErrorMonitor::getInstance().tradeOnMissingOrder();
            return;
        }
        auto && buyOrderBook = getBuyOrderBook(trade.productId);
        auto && sellOrderBook = getSellOrderBook(trade.productId);
        auto &sellOrderList = sellOrderBook.begin()->second;
        auto &buyOrderList = buyOrderBook.rbegin()->second;

        // verify price and size
        if ((sellOrderBook.begin()->first != trade.tradePrice)
            || sellOrderList.empty()
            || buyOrderList.empty()
            || (sellOrderList.begin()->size < trade.tradeSize)
            || (buyOrderList.begin()->size < trade.tradeSize)
        ) {
            ErrorMonitor::getInstance().tradeOnMissingOrder();
            return;
        }

        // modify the orderbook
        auto &&buyOrd = allOrders_.find(buyOrderList.begin()->orderId);
        if (buyOrderList.begin()->size == trade.tradeSize) {
            // don't delete in allOrders_, just mark, the "R" message will do it.
            if (buyOrd != allOrders_.end()) {
                buyOrd->second.TradeDelete();
            }
            buyOrderList.erase(buyOrderList.begin());
        } else {
            // modify the size in allOrders_
            buyOrderList.begin()->size -= trade.tradeSize;
            if (buyOrd != allOrders_.end()) {
                buyOrd->second.size -= trade.tradeSize;
            }
        }

        if (buyOrderList.empty()) {
            buyOrderBook.erase(--buyOrderBook.end());
        }

        auto &&sellOrd = allOrders_.find(sellOrderList.begin()->orderId);
        if (sellOrderList.begin()->size == trade.tradeSize) {
            if (sellOrd != allOrders_.end()) {
                sellOrd->second.TradeDelete();
            }
            sellOrderList.erase(sellOrderList.begin());
        } else {
            sellOrderList.begin()->size -= trade.tradeSize;
            if (sellOrd != allOrders_.end()) {
                sellOrd->second.size -= trade.tradeSize;
            }
        }

        if (sellOrderList.empty()) {
            sellOrderBook.erase(sellOrderBook.begin());
        }


        // print recent trade message =================================
        if (recentTradeProduct_ == trade.productId && recentTradePrice_ == trade.tradePrice) {
            recentTradeSize_ += trade.tradeSize;
        } else {
            recentTradeSize_ = trade.tradeSize;
            recentTradeProduct_ = trade.productId;
            recentTradePrice_ = trade.tradePrice;
        }

#ifndef PROFILE
        std::cout << "--- TRADE --------------------------------------\n";
        std::cout << "product " <<  recentTradeProduct_ << ":"
                << recentTradeSize_ << "@" << (double)(recentTradePrice_/ 100.) << endl;
#endif
    }




    //------------------------------------------------------------
    // print each product orderbook from 5th level
    void OrderBook::printOrderBook(std::ostream &os) const {
        std::stringstream ss;
        os << "--- ORDER BOOK ----------------------------------\n";
        for (auto && iter : productPriceBooks_) {
            auto &&buyOrderBook = iter.second.first;
            auto &&sellOrderBook = iter.second.second;

            os << "Product " << iter.first << endl;

            int count = std::min(5,(int)sellOrderBook.size());
            auto sit = sellOrderBook.begin();
            std::advance(sit,count);

            // print the sell order
            while (count-- > 0) {
                --sit;
                auto &&lst = sit->second;
                os << std::setw(10) << sit->first /100.<< " ";
                for (auto &&sz : lst) {
                    os << " S " << sz.size;
                }
                os << endl;
            }

            // print the buy order
            count = 0;
            for (auto it = buyOrderBook.rbegin(); count<5 && it != buyOrderBook.rend(); ++it,count++) {
                auto &&lst = it->second;
                os << std::setw(10) << it->first /100.<< " ";
                for (auto &&sz : lst) {
                    os << " B " << sz.size;
                }
                os << endl;
            }
        }
    }


}