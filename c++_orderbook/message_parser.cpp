//
// Created by rui zhou on 2020-05-15.
//

#include <cstring>
#include "message_parser.h"
#include "error_monitor.h"

using namespace std;

namespace ob {
    //TODO move to utilities later
    void MessageParser::LogParseError(ParseResult err) {
        switch (err) {
            case ParseResult ::CorruptMessage:
                ErrorMonitor::getInstance().corruptMessage();
                break;
            case ParseResult ::InvalidMsgData:
                ErrorMonitor::getInstance().invalidMsg();
                break;
            case ParseResult ::InvalidProductId:
                ErrorMonitor::getInstance().invalidProductId();
                break;
            default:
                break;
        }
    }


    MessageType MessageParser::getMessageType(char *tokenMsg) {
        uint32_t len = strlen(tokenMsg);
        //check length
        if (len == 0 || len > MAX_MSG_LENGTH) {
            ErrorMonitor::getInstance().corruptMessage();
            return MessageType::Unknown;
        }

        tokenMsg = strtok(tokenMsg, ",");
        switch(tokenMsg[0]) {
            case 'N':
                return MessageType::Add;
            case 'M':
                return MessageType::Modify;

            case 'R':
                return MessageType::Remove;

            case 'X':
                return MessageType::Trade;

            default:
                return MessageType::Unknown;

        }
    }

    bool MessageParser::parseTokenAsUInt64(char *&tk_msg, uint64_t &dest) {
        tk_msg = strtok(NULL, ",");
        if (tk_msg == NULL) {
            return false;
        }
        else {
            char * pEnd = NULL;
            dest = std::strtoull(tk_msg, &pEnd, 10);
            if (*pEnd)
                return false;
        }
        return true;
    }


    // parse order
    bool MessageParser::parseOrder(char *tokenMsg, Order &order) {
        // product id
        if (order.action == MessageType::Add) { //no for R,M
            if (!parseTokenAsInt(tokenMsg, order.productId)) {
                LogParseError(ParseResult::InvalidProductId);
                return false;
            }
        }
        if (order.productId < 0) {
            LogParseError(ParseResult::InvalidProductId);
            return false;
        }


        // order id
        if (!parseTokenAsUInt64(tokenMsg, order.orderId)) {
            LogParseError(ParseResult::InvalidMsgData);
            return false;
        }

        // side
        if (!parseSide(tokenMsg, order.side)) {
            LogParseError(ParseResult::InvalidMsgData);
            return false;
        }

        // size
        if (!parseTokenAsUInt(tokenMsg, order.size)) {
            LogParseError(ParseResult::InvalidMsgData);
            return false;
        }

        if (order.size > MAX_TRADE_SIZE || order.size <=0) {
            LogParseError(ParseResult ::InvalidMsgData);
            return false;
        }

        // price
        double price;
        if (!parsePrice(tokenMsg, price)) {
            LogParseError(ParseResult ::InvalidMsgData);
            return false;
        }

        order.price = price * 100;

        if (order.price > MAX_TRADE_PRICE) {
            LogParseError(ParseResult ::InvalidMsgData);
            return false;
        }

        order.status = OrderStatus::Normal;
        return true;
    }

    // =============================================================
    bool MessageParser::parseTrade(char *tokenMsg, Trade &trd) {
        // product id
        if (!parseTokenAsInt(tokenMsg, trd.productId)) {
            LogParseError(ParseResult::InvalidProductId);
            return false;
        }

        // size
        if (!parseTokenAsUInt(tokenMsg, trd.tradeSize)) {
            LogParseError(ParseResult ::InvalidMsgData);
            return false;
        }

        double price;
        if (!parsePrice(tokenMsg, price)) {
            LogParseError(ParseResult ::InvalidMsgData);
            return false;
        }

        trd.tradePrice = price * 100;
        if (trd.tradePrice > MAX_TRADE_PRICE) {
            LogParseError(ParseResult ::InvalidMsgData);
            return false;
        }

        return true;
    }

    bool MessageParser::parseTokenAsUInt(char *&tk_msg, uint32_t &dest) {
        tk_msg = strtok(NULL, ",");
        if (tk_msg == NULL) {
            return false;
        }
        else {
            char * pEnd = NULL;
            dest = strtoul(tk_msg, &pEnd, 10);
            if (*pEnd)
                return false;
        }
        return true;
    }

    bool MessageParser::parseTokenAsInt(char *&tk_msg, int32_t &dest) {
        tk_msg = strtok(NULL, ",");
        if (tk_msg == NULL) {
            return false;
        }
        else {
            char * pEnd = NULL;
            dest = strtol(tk_msg, &pEnd, 10);
            if (*pEnd)
                return false;
        }
        return true;
    }


    bool MessageParser::parseSide(char *&tk_msg, uint32_t &dest) {
        tk_msg = strtok(NULL, ",");
        if (tk_msg == NULL) {
            return false;
        }
        else {
            if (tk_msg[0]=='S') {
                dest = SELL;
            } else if (tk_msg[0]=='B') {
                dest = BUY;
            } else {
                return false;
            }
        }
        return true;
    }

    bool MessageParser::parsePrice(char *&tk_msg, double &dest) {
        tk_msg = strtok(NULL, " ");
        if (tk_msg == NULL) {
            return false;
        }
        else {
            char * pEnd = NULL;
            //dest = strtol(tk_msg, &pEnd, 10);
            dest = strtod (tk_msg, &pEnd);
            if (*pEnd)
                return false;
        }
        return true;
    }
}