import { HTMLAttributes } from 'react';
import clsx from 'clsx';

interface CardProps extends HTMLAttributes<HTMLDivElement> {
  /** Apply default padding (p-6). Set false for tables/custom layouts. */
  padded?: boolean;
  /** Add hover elevation. */
  hoverable?: boolean;
}

export function Card({ padded = true, hoverable = false, className, children, ...props }: CardProps) {
  return (
    <div
      className={clsx('card', padded && 'card-pad', hoverable && 'card-hoverable', className)}
      {...props}
    >
      {children}
    </div>
  );
}
