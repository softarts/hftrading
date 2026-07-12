//
// Created by rui zhou on 2020-05-15.
//

#include "feed_handler.h"
#include "error_monitor.h"

namespace ob {
    FeedHandler::FeedHandler()
    :parser_(new MessageParser()), orderBook_(new OrderBook())
    {
    };


    // process message
    void FeedHandler::processMessage(const std::string &line) {

        Order order;  //POD,
        Trade trd;

        char *buf = const_cast<char*>(line.c_str());

        MessageType mt = parser_->getMessageType(buf);

        if (mt == MessageType::Unknown) {

            ErrorMonitor::getInstance().corruptMessage();

        } else if (mt == MessageType::Trade) {

            if (parser_->parseTrade(buf, trd)) {
                orderBook_->handleTrade(trd);
            } else {
                ErrorMonitor::getInstance().corruptMessage();
            }

        } else {
            order.action = mt;
            if (parser_->parseOrder(buf, order)) {
                orderBook_->handleOrder(order);
            }
        }

    }

    void FeedHandler::printCurrentOrderBook(std::ostream &os) const {
        orderBook_->printOrderBook(os);
    }

}