# Exercise 1: Order Book Manager Requirements

Based on the ATS Interview Exercises-1.pdf, here are the detailed requirements for the Order Book Manager:

## Problem
Design and implement an order book manager that reads a sequence of order and trade messages, handles error conditions, and disseminates price information from the constructed order book.

## Rules
- When an order crosses the opposite limit (e.g., if we receive Buy @ 10 on an existing best Sell @ 10 or 9), a trade message is expected.
- When receiving a trade message, the feed handler must remove order quantity starting from the best price (greatest for buys, lowest for sells) and for a given price from the first-received order.

## Messages Format
The exchange feed provides the following message types:

**Order:** `action, productid, orderid, side, quantity, price` (e.g., `N,388,123,B,9,1000`)
- `productid` = unique product identifier on the exchange, positive integer
- `action` = `N` (new), `R` (remove), `M` (modify)
- `orderid` = unique positive integer to identify each order
- `side` = `B` (buy), `S` (sell)
- `quantity` = positive integer indicating maximum quantity
- `price` = double indicating max/min price

**Trade:** `action, productid, quantity, price` (e.g., `X,388,2,1025`)
- `action` = `X` (trade)
- `productid` = unique product identifier
- `quantity` = amount that traded
- `price` = price at which the trade happened

## Technical Requirements
0. Read a sequence of messages from a text file.
1. Construct an in-memory representation of the current state of the order book from the messages.
2. Every 10th message and on exit, write out a human-readable representation of the book down to the 5th level for each product id.
3. Write out the total quantity traded at the most recent trade price on every trade message. Example: `X,5,2,1025 => product 5: 2@1025`
4. Error handling and summary on exit for garbage inputs:
   - Corrupted messages
   - Duplicated order ids (duplicate adds)
   - Trades with no corresponding order
   - Removes with no corresponding order
   - Best sell order price at or below best buy order price but no trades occur
   - Negative, missing, or out-of-bounds prices, quantities, order ids
   - Negative product id

*Note: Our current implementation uses a slightly modified format (`N, ts, symbol, orderId, side, qty, price`) provided by the `TestDataTool`, which includes a timestamp for latency measurement purposes.*
