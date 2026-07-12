//
// Created by rui zhou on 2020-05-15.
//

#ifndef ORDERBOOK_MESSAGE_PARSER_H
#define ORDERBOOK_MESSAGE_PARSER_H


#include "common_def.h"
#include "orderbook_types.h"

namespace ob {

    class MessageParser {
    public:
        bool parseOrder(char *tokenMsg, Order &order);

        MessageType getMessageType(char *tokenMsg);

        bool parseTokenAsUInt64(char *&tk_msg, uint64_t &dest);

        bool parseTokenAsUInt(char *&tk_msg, uint32_t &dest);

        bool parseTokenAsInt(char *&tk_msg, int32_t &dest);

        bool parseSide(char *&tk_msg, uint32_t &dest);

        bool parsePrice(char *&tk_msg, double &dest);

        bool parseTrade(char *tokenMsg, Trade &trd);

        void LogParseError(ParseResult err);
    };
}
#endif //ORDERBOOK_MESSAGE_PARSER_H
