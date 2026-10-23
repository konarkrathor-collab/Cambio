import { useState } from 'react';
import { selectPair, useStore } from '../app/runtime.js';
import Panel from './Panel.jsx';
import PairPicker from './PairPicker.jsx';
import OrderBook from './OrderBook.jsx';
import DepthChart from './DepthChart.jsx';
import OrderTicket from './OrderTicket.jsx';
import MyOrders from './MyOrders.jsx';
import TradesFeed from './TradesFeed.jsx';
import AccountPanel from './AccountPanel.jsx';

export default function MarketView() {
  const pair = useStore((s) => s.pair);
  const [traderId, setTraderId] = useState('1');
  return (
    <div className="grid-market">
      <Panel id="book" title={`Order book · ${pair}`} className="area-book" actions={<PairPicker value={pair} onSelect={selectPair} />}>
        <OrderBook />
        <DepthChart />
      </Panel>
      <div className="col">
        <Panel id="ticket" title="Order ticket">
          <OrderTicket traderId={traderId} onTrader={setTraderId} />
        </Panel>
        <Panel id="mine" title="My resting orders">
          <MyOrders />
        </Panel>
      </div>
      <div className="col">
        <Panel id="trades" title={`Trades · ${pair}`} className="grow">
          <TradesFeed />
        </Panel>
        <Panel id="account" title="Account">
          <AccountPanel traderId={traderId} onTrader={setTraderId} />
        </Panel>
      </div>
    </div>
  );
}
