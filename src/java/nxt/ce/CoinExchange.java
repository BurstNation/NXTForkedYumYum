/*
 * Copyright © 2016-2017 Jelurida IP B.V.
 *
 * See the LICENSE.txt file at the top-level directory of this distribution
 * for licensing information.
 *
 * Unless otherwise agreed in a custom licensing agreement with Jelurida B.V.,
 * no part of the Nxt software, including this file, may be copied, modified,
 * propagated, or distributed except according to the terms contained in the
 * LICENSE.txt file.
 *
 * Removal or modification of this copyright notice is prohibited.
 *
 */
package nxt.ce;

import nxt.Nxt;
import nxt.account.AccountLedger;
import nxt.account.AccountLedger.LedgerEvent;
import nxt.account.BalanceHome;
import nxt.blockchain.Block;
import nxt.blockchain.Chain;
import nxt.blockchain.Transaction;
import nxt.db.DbClause;
import nxt.db.DbIterator;
import nxt.db.DbKey;
import nxt.db.EntityDbTable;
import nxt.db.VersionedEntityDbTable;
import nxt.util.Listener;
import nxt.util.Listeners;

import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Coin exchange home
 */
public final class CoinExchange {

    public enum Event {
        TRADE
    }

    private static final Listeners<Trade, Event> listeners = new Listeners<>();

    public static boolean addListener(Listener<Trade> listener, Event eventType) {
        return listeners.addListener(listener, eventType);
    }

    public static boolean removeListener(Listener<Trade> listener, Event eventType) {
        return listeners.removeListener(listener, eventType);
    }

    private static final BigDecimal askConstant = new BigDecimal(1L, MathContext.DECIMAL128);
    private static final DbKey.LongKeyFactory<Order> orderDbKeyFactory =
            new DbKey.LongKeyFactory<Order>("id") {
        @Override
        public DbKey newKey(Order order) {
            return order.dbKey;
        }
    };
    private static final VersionedEntityDbTable<Order> orderTable =
            new VersionedEntityDbTable<Order>("PUBLIC.coin_order_fxt", orderDbKeyFactory) {
        @Override
        protected Order load(Connection con, ResultSet rs, DbKey dbKey) throws SQLException {
            return new Order(rs, dbKey);
        }
        @Override
        protected void save(Connection con, Order order) throws SQLException {
            order.save(con, schemaTable);
        }
        @Override
        protected String defaultSort() {
            return " ORDER BY creation_height DESC ";
        }
    };
    private static final DbKey.HashHashKeyFactory<Trade> tradeDbKeyFactory =
            new DbKey.HashHashKeyFactory<Trade>("order_full_hash", "order_id", "match_full_hash", "match_id") {
        @Override
        public DbKey newKey(Trade trade) {
            return trade.dbKey;
        }
    };
    private static final EntityDbTable<Trade> tradeTable =
            new EntityDbTable<Trade>("PUBLIC.coin_trade_fxt", tradeDbKeyFactory) {
        @Override
        protected Trade load(Connection con, ResultSet rs, DbKey dbKey) throws SQLException {
            return new Trade(rs, dbKey);
         }
        @Override
        protected void save(Connection con, Trade trade) throws SQLException {
            trade.save(con, schemaTable);
        }
    };

    private CoinExchange() {}

    public static void init() {}

    private static Order getNextBidOrder(int chainId, int exchangeId) {
        try (Connection con = orderTable.getConnection();
             PreparedStatement pstmt = con.prepareStatement("SELECT * FROM " + orderTable.getSchemaTable()
                     + " WHERE chain_id = ? AND exchange_id = ? AND latest = TRUE"
                     + " ORDER BY bid_price DESC, creation_height ASC, transaction_height ASC, transaction_index ASC"
                     + " LIMIT 1")) {
            pstmt.setInt(1, chainId);
            pstmt.setInt(2, exchangeId);
            try (DbIterator<Order> askOrders = orderTable.getManyBy(con, pstmt, true)) {
                return askOrders.hasNext() ? askOrders.next() : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        }
    }

    private static Order getNextAskOrder(int chainId, int exchangeId) {
        try (Connection con = orderTable.getConnection();
             PreparedStatement pstmt = con.prepareStatement("SELECT * FROM " + orderTable.getSchemaTable()
                     + " WHERE chain_id = ? AND exchange_id = ? AND latest = TRUE"
                     + " ORDER BY ask_price ASC, creation_height ASC, transaction_height ASC, transaction_index ASC"
                     + " LIMIT 1")) {
            pstmt.setInt(1, chainId);
            pstmt.setInt(2, exchangeId);
            try (DbIterator<Order> askOrders = orderTable.getManyBy(con, pstmt, true)) {
                return askOrders.hasNext() ? askOrders.next() : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        }
    }

    /**
     * Add a coin exchange order
     *
     * @param   tx                  Transaction
     * @param   attachment          Attachment
     */
    static void addOrder(Transaction tx, OrderIssueAttachment attachment) {
        Order order = new Order(tx, attachment);
        orderTable.insert(order);
        matchOrders(attachment);
    }

    /**
     * Remove a coin exchange order
     *
     * @param   orderId             Order identifier
     */
    static void removeOrder(long orderId) {
        orderTable.delete(getOrder(orderId));
    }

    /**
     * Return the number of coin exchange orders
     *
     * @return                      Order count
     */
    public static int getOrderCount() {
        return orderTable.getCount();
    }

    /**
     * Return a coin exchange order
     *
     * @param   orderId             Order identifier
     * @return                      Coin exchange order or null if not found
     */
    public static Order getOrder(long orderId) {
        return orderTable.get(orderDbKeyFactory.newKey(orderId));
    }

    /**
     * Return orders matching the supplied criteria.  All orders will be returned
     * if no search criteria are provided.  The returned results will be sorted
     * by price in descending order.
     *
     * @param   accountId           Account identifier (0 if no account criteria)
     * @param   chainId             Chain identifier (0 if no chain criteria)
     * @param   exchangeId          Exchange identifier (0 if no exchange criteria)
     * @param   from                Starting index within search results
     * @param   to                  Ending index within search results
     * @return                      Database iterator
     */
    public static DbIterator<Order> getOrders(long accountId, int chainId, int exchangeId, int from, int to) {
        List<DbClause> dbClauses = new ArrayList<>();
        if (accountId != 0) {
            dbClauses.add(new DbClause.LongClause("account_id", accountId));
        }
        if (chainId != 0) {
            dbClauses.add(new DbClause.IntClause("chain_id", chainId));
        }
        if (exchangeId != 0) {
            dbClauses.add(new DbClause.IntClause("exchange_id", exchangeId));
        }
        DbClause dbClause = null;
        if (dbClauses.isEmpty()) {
            dbClause = DbClause.EMPTY_CLAUSE;
        } else {
            for (DbClause clause : dbClauses) {
                if (dbClause == null) {
                    dbClause = clause;
                } else {
                    dbClause = dbClause.and(clause);
                }
            }
        }
        return orderTable.getManyBy(dbClause, from, to, "ORDER BY bid_price DESC");
    }

    /**
     * Add a coin exchange trade
     *
     * @param   exchangeQuantity    Number of coins received
     * @param   exchangePrice       Exchange price
     * @param   order               Order
     * @param   match               Match order
     * @return                      Trade
     */
    private static Trade addTrade(long exchangeQuantity, long exchangePrice, Order order, Order match) {
        Trade trade = new Trade(exchangeQuantity, exchangePrice, order, match);
        tradeTable.insert(trade);
        listeners.notify(trade, Event.TRADE);
        return trade;
    }

    /**
     * Return a coin exchange trade
     *
     * @param   orderFullHash       Coin exchange order full hash
     * @param   matchFullHash       Matching order full hash
     * @return                      Coin exchange trade or null if not found
     */
    public static Trade getTrade(byte[] orderFullHash, byte[] matchFullHash) {
        return tradeTable.get(tradeDbKeyFactory.newKey(orderFullHash, matchFullHash));
    }

    /**
     * Return the number of coin exchange trades
     *
     * @return                      Trade coin
     */
    public static int getTradeCount() {
        return tradeTable.getCount();
    }

    /**
     * Return trades matching the supplied criteria.  All trades will be returned
     * if no search criteria are provided.  The returned results will be sorted
     * by block height in descending order.
     *
     * @param   accountId           Account identifier (0 if no account criteria)
     * @param   chainId             Chain identifier (0 if no chain criteria)
     * @param   exchangeId          Exchange identifier (0 if no exchange criteria)
     * @param   orderFullHash       Coin exchange order full hash (null or zero-length if no order criteria)
     * @param   from                Starting index within search results
     * @param   to                  Ending index within search results
     * @return                      Database iterator
     */
    public static DbIterator<Trade> getTrades(long accountId, int chainId, int exchangeId, byte[] orderFullHash,
                                              int from, int to) {
        List<DbClause> dbClauses = new ArrayList<>();
        if (accountId != 0) {
            dbClauses.add(new DbClause.LongClause("account_id", accountId));
        }
        if (chainId != 0) {
            dbClauses.add(new DbClause.IntClause("chain_id", chainId));
        }
        if (exchangeId != 0) {
            dbClauses.add(new DbClause.IntClause("exchange_id", exchangeId));
        }
        if (orderFullHash != null && orderFullHash.length != 0) {
            dbClauses.add(new DbClause.HashClause("order_full_hash", "order_id", orderFullHash));
        }
        DbClause dbClause = null;
        if (dbClauses.isEmpty()) {
            dbClause = DbClause.EMPTY_CLAUSE;
        } else {
            for (DbClause clause : dbClauses) {
                if (dbClause == null) {
                    dbClause = clause;
                } else {
                    dbClause = dbClause.and(clause);
                }
            }
        }
        return tradeTable.getManyBy(dbClause, from, to, "ORDER BY height DESC, db_id DESC");
    }

    /**
     * Match orders after a new order is added
     *
     * The bid orders are orders for the current chain and the ask orders are
     * orders for the exchange chain.
     *
     * @param   attachment          Order issue attachment
     */
    private static void matchOrders(OrderIssueAttachment attachment) {
        Order askOrder;
        Order bidOrder;
        Chain chain = attachment.getChain();
        int bidDecimals = chain.getDecimals();
        Chain exchangeChain = attachment.getExchangeChain();
        int askDecimals = exchangeChain.getDecimals();
        //
        // Match orders until the ask price is greater than the bid price
        //
        while ((askOrder = getNextAskOrder(exchangeChain.getId(), chain.getId())) != null &&
                    (bidOrder = getNextBidOrder(chain.getId(), exchangeChain.getId())) != null) {
            BigDecimal bidOrderBid = new BigDecimal(bidOrder.getBidPrice(), MathContext.DECIMAL128)
                    .movePointLeft(bidDecimals);
            BigDecimal bidOrderAsk = new BigDecimal(bidOrder.getAskPrice(), MathContext.DECIMAL128)
                    .movePointLeft(askDecimals);
            BigDecimal askOrderBid = new BigDecimal(askOrder.getBidPrice(), MathContext.DECIMAL128)
                    .movePointLeft(askDecimals);
            BigDecimal askOrderAsk = new BigDecimal(askOrder.getAskPrice(), MathContext.DECIMAL128)
                    .movePointLeft(bidDecimals);
            if (askOrderAsk.compareTo(bidOrderBid) > 0) {
                break;
            }
            //
            // We will use the exchange price from the older order.  The number of coins
            // that can be exchanged is limited by the order quantities.
            //
            // The bid order exchange Coin A for Coin B while the ask order exchanges
            // Coin B for Coin A.
            //
            BigDecimal bidPrice;            // Coin A / Coin B
            BigDecimal askPrice;            // Coin B / Coin A
            long bidQuantity;               // Amount of Coin B to exchange
            long askQuantity;               // Amount of Coin A to exchange
            boolean isBuy = (askOrder.getHeight() < bidOrder.getHeight()) ||
                    (askOrder.getHeight() == bidOrder.getHeight() &&
                        (askOrder.getTransactionHeight() < bidOrder.getTransactionHeight() ||
                            (askOrder.getTransactionHeight() == bidOrder.getTransactionHeight() &&
                                askOrder.getTransactionIndex() < bidOrder.getTransactionIndex())));

            if (isBuy) {
                bidPrice = askOrderAsk;
                askPrice = askOrderBid;
            } else {
                bidPrice = bidOrderBid;
                askPrice = bidOrderAsk;
            }
            bidQuantity = new BigDecimal(bidOrder.getQuantity(), MathContext.DECIMAL128)
                    .movePointLeft(bidDecimals)
                    .multiply(askPrice)
                    .movePointRight(askDecimals)
                    .longValue();
            if (bidQuantity == 0) {
                bidQuantity = 1;
            }
            if (bidQuantity >= askOrder.getQuantity() - 1) {
                bidQuantity = askOrder.getQuantity();
            }
            askQuantity = new BigDecimal(askOrder.getQuantity(), MathContext.DECIMAL128)
                    .movePointLeft(askDecimals)
                    .multiply(bidPrice)
                    .movePointRight(bidDecimals)
                    .longValue();
            if (askQuantity == 0) {
                askQuantity = 1;
            }
            if (askQuantity >= bidOrder.getQuantity() - 1) {
                askQuantity = bidOrder.getQuantity();
            }
            //
            // Create the trade for bid order
            //
            addTrade(bidQuantity, bidPrice.movePointRight(bidDecimals).longValue(), bidOrder, askOrder);
            //
            // Create the trade for the ask order
            //
            addTrade(askQuantity, askPrice.movePointRight(askDecimals).longValue(), askOrder, bidOrder);
            //
            // Update the buyer balances
            //
            bidOrder.updateQuantity(bidOrder.getQuantity() - askQuantity);
            BalanceHome.Balance buyerBalance = chain.getBalanceHome().getBalance(bidOrder.getAccountId());
            AccountLedger.LedgerEventId bidEventId =
                    AccountLedger.newEventId(bidOrder.getId(), bidOrder.getFullHash(), chain);
            buyerBalance.addToBalance(LedgerEvent.COIN_EXCHANGE_TRADE, bidEventId, -askQuantity);
            buyerBalance = exchangeChain.getBalanceHome().getBalance(bidOrder.getAccountId());
            buyerBalance.addToBalanceAndUnconfirmedBalance(LedgerEvent.COIN_EXCHANGE_TRADE, bidEventId,
                    bidQuantity);
            //
            // Update the seller balances
            //
            askOrder.updateQuantity(askOrder.getQuantity() - bidQuantity);
            BalanceHome.Balance sellerBalance = exchangeChain.getBalanceHome().getBalance(askOrder.getAccountId());
            AccountLedger.LedgerEventId askEventId = AccountLedger.newEventId(askOrder.getId(),
                    askOrder.getFullHash(), exchangeChain);
            sellerBalance.addToBalance(LedgerEvent.COIN_EXCHANGE_TRADE, askEventId, -bidQuantity);
            sellerBalance = chain.getBalanceHome().getBalance(askOrder.getAccountId());
            sellerBalance.addToBalanceAndUnconfirmedBalance(LedgerEvent.COIN_EXCHANGE_TRADE, askEventId,
                    askQuantity);
        }
    }

    /**
     * Define a coin exchange order
     *
     * 'id' and 'fullHash' identify the coin exchange order
     * 'chainId' is the chain identifier for the coin being exchanged
     * 'exchangeId' is the chain identifier for the requested coin
     * 'quantity' is the number of coins being exchanged (updated after each trade)
     * 'bidPrice' is the bid price for the requested coin and is the exchange order price
     * 'askPrice' is the ask price for coin being exchanged and is 1/bidPrice
     */
    public final static class Order {
        private final DbKey dbKey;
        private final long id;
        private final byte[] fullHash;
        private final short transactionIndex;
        private final int transactionHeight;
        private final int creationHeight;
        private final long accountId;
        private final int chainId;
        private final int exchangeId;
        private long quantity;
        private final long bidPrice;
        private final long askPrice;

        private Order(Transaction transaction, OrderIssueAttachment attachment) {
            this.id = transaction.getId();
            this.fullHash = transaction.getFullHash();
            this.transactionIndex = transaction.getIndex();
            this.transactionHeight = transaction.getHeight();
            this.creationHeight = Nxt.getBlockchain().getHeight();
            this.accountId = transaction.getSenderId();
            this.chainId = attachment.getChain().getId();
            this.exchangeId = attachment.getExchangeChain().getId();
            this.quantity = attachment.getQuantityQNT();
            this.bidPrice = attachment.getPriceNQT();
            Chain chain = Chain.getChain(chainId);
            Chain exchange = Chain.getChain(exchangeId);
            BigDecimal bidValue = new BigDecimal(this.bidPrice, MathContext.DECIMAL128)
                    .movePointLeft(chain.getDecimals());
            long askValue = askConstant.divide(bidValue, MathContext.DECIMAL128)
                    .movePointRight(exchange.getDecimals()).longValue();
            this.askPrice = (askValue > 0 ? askValue : 1);
            this.dbKey = orderDbKeyFactory.newKey(this.id);
        }

        private Order(ResultSet rs, DbKey dbKey) throws SQLException {
            this.dbKey = dbKey;
            this.id = rs.getLong("id");
            this.fullHash = rs.getBytes("full_hash");
            this.transactionIndex = rs.getShort("transaction_index");
            this.transactionHeight = rs.getInt("transaction_height");
            this.creationHeight = rs.getInt("transaction_height");
            this.accountId = rs.getLong("account_id");
            this.chainId = rs.getInt("chain_id");
            this.exchangeId = rs.getInt("exchange_id");
            this.quantity = rs.getLong("quantity");
            this.bidPrice = rs.getLong("bid_price");
            this.askPrice = rs.getLong("ask_price");
        }

        private void save(Connection con, String table) throws SQLException {
            try (PreparedStatement pstmt = con.prepareStatement("MERGE INTO " + table
                    + " (id, account_id, chain_id, exchange_id, quantity, bid_price, ask_price, "
                    + "full_hash, creation_height, height, transaction_height, transaction_index, latest) "
                    + "KEY(id, height, full_hash) "
                    + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)" )) {
                int i = 0;
                pstmt.setLong(++i, id);
                pstmt.setLong(++i, accountId);
                pstmt.setInt(++i, chainId);
                pstmt.setInt(++i, exchangeId);
                pstmt.setLong(++i, quantity);
                pstmt.setLong(++i, bidPrice);
                pstmt.setLong(++i, askPrice);
                pstmt.setBytes(++i, fullHash);
                pstmt.setInt(++i, creationHeight);
                pstmt.setInt(++i, Nxt.getBlockchain().getHeight());
                pstmt.setInt(++i, transactionHeight);
                pstmt.setShort(++i, transactionIndex);
                pstmt.executeUpdate();
            }
        }

        private void updateQuantity(long quantity) {
            this.quantity = quantity;
            if (quantity > 0) {
                orderTable.insert(this);
            } else if (quantity == 0) {
                orderTable.delete(this);
            } else {
                throw new IllegalArgumentException("Negative quantity: " + quantity
                        + " for order: " + Long.toUnsignedString(getId()));
            }
        }

        public final long getId() {
            return id;
        }

        public final byte[] getFullHash() {
            return fullHash;
        }

        public final long getAccountId() {
            return accountId;
        }

        public final int getChainId() {
            return chainId;
        }

        public final int getExchangeId() {
            return exchangeId;
        }

        public final long getBidPrice() {
            return bidPrice;
        }

        public final long getAskPrice() {
            return askPrice;
        }

        public final long getQuantity() {
            return quantity;
        }

        public final int getHeight() {
            return creationHeight;
        }

        public final int getTransactionIndex() {
            return transactionIndex;
        }

        public final int getTransactionHeight() {
            return transactionHeight;
        }

        @Override
        public String toString() {
            return "Coin exchange order: " + Long.toUnsignedString(id)
                    + " account: " + Long.toUnsignedString(accountId)
                    + " chain: " + Chain.getChain(chainId).getName()
                    + " exchange: " + Chain.getChain(exchangeId).getName()
                    + " quantity: " + quantity
                    + " bid: " + bidPrice
                    + " ask: " + askPrice
                    + " height: " + creationHeight
                    + " transactionIndex: " + transactionIndex
                    + " transactionHeight: " + transactionHeight;
        }
    }

    /**
     * Define a coin exchange trade
     *
     * A coin exchange consists of two trades.  One trade is entered in the trade table
     * for our coin and another trade is entered in the trade table for the
     * requested coin.  For each trade, the buyer is the order owner for the owning child chain and
     * the seller is the order owner for the requested coin child chain.
     *
     * 'exchangeId' is the child chain identifier for the exchanged coin
     * 'exchangeQuantity' is the exchanged coin amount
     * 'exchangePrice' is the price for the exchanged coins
     * 'exchangeQuantity * exchangePrice' is the number of coins spent for the exchange
     */
    public final static class Trade {
        private final DbKey dbKey;
        private final int chainId;
        private final int exchangeId;
        private final long blockId;
        private final int height;
        private final int timestamp;
        private final long exchangeQuantity;
        private final long exchangePrice;
        private final long accountId;
        private final long orderId;
        private final byte[] orderFullHash;
        private final long matchId;
        private final byte[] matchFullHash;

        private Trade(long exchangeQuantity, long exchangePrice, Order order, Order match) {
            Block block = Nxt.getBlockchain().getLastBlock();
            this.blockId = block.getId();
            this.height = block.getHeight();
            this.timestamp = block.getTimestamp();
            this.chainId = order.getChainId();
            this.exchangeId = order.getExchangeId();
            this.accountId = order.getAccountId();
            this.orderId = order.getId();
            this.orderFullHash = order.getFullHash();
            this.exchangeQuantity = exchangeQuantity;
            this.exchangePrice = exchangePrice;
            this.matchId = match.getId();
            this.matchFullHash = match.getFullHash();
            dbKey = tradeDbKeyFactory.newKey(this.orderFullHash, this.orderId, this.matchFullHash, this.matchId);
        }

        private Trade(ResultSet rs, DbKey dbKey) throws SQLException {
            this.dbKey = dbKey;
            this.blockId = rs.getLong("block_id");
            this.height = rs.getInt("height");
            this.timestamp = rs.getInt("timestamp");
            this.chainId = rs.getInt("chain_id");
            this.exchangeId = rs.getInt("exchange_id");
            this.accountId = rs.getLong("account_id");
            this.orderId = rs.getLong("order_id");
            this.orderFullHash = rs.getBytes("order_full_hash");
            this.exchangeQuantity = rs.getLong("exchange_quantity");
            this.exchangePrice = rs.getLong("exchange_price");
            this.matchId = rs.getLong("match_id");
            this.matchFullHash = rs.getBytes("match_full_hash");
        }

        private void save(Connection con, String table) throws SQLException {
            try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO " + table
                    + "(chain_id, exchange_id, block_id, height, timestamp, exchange_quantity, exchange_price, "
                    + "account_id, order_id, order_full_hash, match_id, match_full_hash) "
                    + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                int i = 0;
                pstmt.setInt(++i, chainId);
                pstmt.setInt(++i, exchangeId);
                pstmt.setLong(++i, blockId);
                pstmt.setInt(++i, height);
                pstmt.setInt(++i, timestamp);
                pstmt.setLong(++i, exchangeQuantity);
                pstmt.setLong(++i, exchangePrice);
                pstmt.setLong(++i, accountId);
                pstmt.setLong(++i, orderId);
                pstmt.setBytes(++i, orderFullHash);
                pstmt.setLong(++i, matchId);
                pstmt.setBytes(++i, matchFullHash);
                pstmt.executeUpdate();
            }
        }

        public int getChainId() {
            return chainId;
        }

        public int getExchangeId() {
            return exchangeId;
        }

        public long getBlockId() {
            return blockId;
        }

        public int getHeight() {
            return height;
        }

        public int getTimestamp() {
            return timestamp;
        }

        public long getExchangeQuantity() {
            return exchangeQuantity;
        }

        public long getExchangePrice() {
            return exchangePrice;
        }

        public long getAccountId() {
            return accountId;
        }

        public long getOrderId() {
            return orderId;
        }

        public byte[] getOrderFullHash() {
            return orderFullHash;
        }

        public long getMatchId() {
            return matchId;
        }

        public byte[] getMatchFullHash() {
            return matchFullHash;
        }

        @Override
        public String toString() {
            return "Coin exchange trade: " + Chain.getChain(exchangeId).getName()
                    + " chain: " + Chain.getChain(chainId).getName()
                    + " order: " + Long.toUnsignedString(orderId)
                    + " match: " + Long.toUnsignedString(matchId)
                    + " account: " + Long.toUnsignedString(accountId)
                    + " exchange price: " + exchangePrice
                    + " exchange quantity: " + exchangeQuantity
                    + " height: " + height;
        }
    }
}