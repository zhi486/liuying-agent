import { useLayoutEffect, useRef, useState, type InputHTMLAttributes } from 'react';

type ChoiceInputProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> & {
  variant?: 'checkbox' | 'switch';
};

export function ChoiceInput({ variant = 'checkbox', onChange, ...props }: ChoiceInputProps) {
  const [interactions, setInteractions] = useState(0);
  const interacted = interactions > 0;
  const input = useRef<HTMLInputElement>(null);
  const control = useRef<HTMLSpanElement>(null);
  const previous = useRef<boolean | undefined>(undefined);
  useLayoutEffect(() => {
    const checked = input.current?.checked;
    // 只在真实选中值改变后回弹；异步提交中或失败不能预演成功。
    if (
      interacted &&
      previous.current !== checked &&
      previous.current !== undefined &&
      control.current
    )
      control.current.dataset.spring = checked ? 'arrive' : 'return';
    previous.current = checked;
  });
  return (
    <span ref={control} className="choice-control" data-kind={variant} data-interacted={interacted}>
      <input
        {...props}
        ref={input}
        type="checkbox"
        onChange={(event) => {
          if (props.disabled) return;
          setInteractions((value) => value + 1);
          onChange?.(event);
        }}
      />
      <span className="choice-visual" aria-hidden="true">
        {variant === 'checkbox' && (
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none">
            {/* 使用单根中心线，才能从短边连续描画至长边；普通填充图标无法描边。 */}
            <path
              d="M5 12l4 4L19 6"
              pathLength="1"
              stroke="currentColor"
              strokeWidth="3"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        )}
      </span>
    </span>
  );
}
