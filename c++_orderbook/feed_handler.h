//
// Created by rui zhou on 2020-05-15.
//

#ifndef ORDERBOOK_FEED_HANDLER_H
#define ORDERBOOK_FEED_HANDLER_H



#include <memory>
#include "message_parser.h"
#include "order_book.h"

namespace ob {
    class MessageParser;
    class OrderBook;

    class FeedHandler{
        friend class ObTester;
    private:
        std::unique_ptr<MessageParser> parser_;
        std::unique_ptr<OrderBook> orderBook_;
    public:
        FeedHandler();

        void processMessage(const std::string &line);

        void printCurrentOrderBook(std::ostream &os) const;

    };
}
#endif //ORDERBOOK_FEED_HANDLER_H
